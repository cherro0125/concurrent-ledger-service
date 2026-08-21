# Concurrent Ledger Service

A double-entry ledger service: accounts hold money, clients post transfers
between accounts concurrently, and sometimes retry. State lives entirely
in memory.

## Running it

```
./gradlew bootRun
```

Requires Java 21+ on your `PATH`; the included Gradle wrapper handles
everything else. The service listens on `http://localhost:8080`.

## Running the tests

```
./gradlew test
```

48 tests, including a heavy-load stress test (32 threads, 64,000 transfers
across a shared pool of 8 accounts) and an idempotency race test (16
threads, one key, synchronized start) — see
`src/test/java/com/ledger/core/ConcurrencyTest.java`.

## API

### Create an account

```
POST /accounts
Content-Type: application/json

{"initialBalanceMinorUnits": 1000}
```

The body is optional; an omitted or missing `initialBalanceMinorUnits`
defaults to 0. Returns `201 Created`:

```json
{"accountId": "3f2b...", "balanceMinorUnits": 1000}
```

### Read a balance

```
GET /accounts/{accountId}/balance
```

Returns `200 OK` with the same shape as above, or `404 Not Found` if the
account doesn't exist.

### Post a transfer

```
POST /transfers
Idempotency-Key: <client-generated key>
Content-Type: application/json

{"fromAccountId": "...", "toAccountId": "...", "amountMinorUnits": 500}
```

| Outcome | Status | Body |
|---|---|---|
| Success | `200` | `{"status": "SUCCESS"}` |
| Insufficient funds | `409` | `{"message": "Insufficient funds"}` |
| Either account doesn't exist | `404` | `{"message": "Account not found: <id>"}` |
| Same idempotency key, different parameters | `409` | `{"message": "Idempotency key already used with different transfer parameters: <key>"}` |
| Invalid input (self-transfer, zero/negative amount, missing/blank key, malformed account id) | `400` | `{"message": "..."}` |

### End-to-end example

```bash
FROM=$(curl -s -X POST localhost:8080/accounts -d '{"initialBalanceMinorUnits":1000}' -H 'Content-Type: application/json' | jq -r .accountId)
TO=$(curl -s -X POST localhost:8080/accounts | jq -r .accountId)

curl -X POST localhost:8080/transfers \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: 3f9c1e2a-...' \
  -d "{\"fromAccountId\":\"$FROM\",\"toAccountId\":\"$TO\",\"amountMinorUnits\":400}"
# {"status":"SUCCESS"}

curl localhost:8080/accounts/$FROM/balance
# {"accountId":"...","balanceMinorUnits":600}
```

## Guarantees

- **Atomicity.** A transfer debits one account and credits another, or
  leaves both unchanged — including if a step throws partway through
  (e.g. an arithmetic overflow crediting the destination): the debit is
  rolled back before the exception propagates, so "or nothing changes"
  holds even in that edge case, not just the happy path.
- **Balances never go negative.** Enforced by an atomic check-and-debit
  under the source account's own lock. A transfer that can't be fully
  funded returns `InsufficientFunds` without mutating either balance.
- **Idempotency, including the in-flight case.** A transfer's
  `Idempotency-Key` is claimed atomically; a concurrent retry with the
  same key waits on the original attempt rather than re-executing it,
  even if that retry arrives while the original is still running. A
  completed key always replays its original outcome. A key reused with
  *different* transfer parameters is rejected rather than silently
  replayed. A failed (exceptional) original attempt evicts its key, so a
  genuine retry after a transient failure gets a fresh attempt instead of
  permanently replaying it.
- **Correctness under concurrent load.** Account locks are acquired in a
  fixed global order (by account ID, never by "from"/"to") so no thread
  interleaving can deadlock, regardless of how many accounts or threads
  are involved. Transfers on disjoint account pairs never contend for the
  same lock and proceed fully in parallel.

## Architecture

- **`com.ledger.core`** — plain Java, no framework dependency, no I/O.
  `AccountId`/`Money`/`Account` domain model, `TransferService` (atomic
  transfer + lock ordering), `IdempotentTransferService` (the idempotency
  wrapper), and `AccountRepository` — a port, not an implementation.
- **`com.ledger.store`** — `InMemoryAccountRepository`, the only
  implementation of `AccountRepository`. Swapping in a real storage
  engine is a change confined to this package plus one line in the
  Spring wiring; `core` never imports from `store`.
- **`com.ledger.api`** — Spring MVC controllers, request/response DTOs,
  and exception-to-status-code mapping. `LedgerConfiguration` is the
  single place Spring wiring touches the plain-Java core.

## Trade-offs

- **No persistence, as required.** State lives entirely in memory behind
  the `AccountRepository` interface. Restarting the service loses all
  accounts and idempotency records.
- **No idempotency-key TTL.** A completed key's result is cached forever
  (it has to be — a repeated key must always see the same answer), and
  only an exceptional attempt evicts its key. In a real deployment this
  map grows unboundedly over the service's lifetime; it would need a
  time-based eviction policy once a key is old enough that no legitimate
  retry could still arrive for it.
- **Simplified ledger model.** Accounts store a single running balance,
  not an append-only log of individual transaction entries. A production
  double-entry ledger typically also records an immutable audit trail
  (so a balance can be reconstructed or reconciled independently of the
  live figure); this service only tracks the current balance.
- **Non-fair locks.** `ReentrantLock`'s default favors throughput over
  strict per-request ordering — under sustained heavy contention on one
  account, a specific waiting thread isn't guaranteed FIFO service. A
  fair lock (`new ReentrantLock(true)`) would trade some throughput for
  that guarantee; not worth it at this scale.
- **Blocking, not reactive.** The core uses plain blocking
  `java.util.concurrent` primitives (`ReentrantLock`, `CompletableFuture.
  join()`) on a synchronous Spring MVC stack, not a reactive/non-blocking
  one. Simpler to write and defend correctly, at the cost of tying up a
  request-handling thread for the duration of a lock wait — a deliberate
  fit for this scope, not necessarily for a service under extreme load.
- **Account creation accepts an optional initial balance.** Not literally
  in the spec, but transfers need some way for money to enter the system
  in the first place; a real product would more likely fund accounts
  through an explicit deposit or external-transfer mechanism.
- **`long` minor units, not `BigDecimal`.** Chosen for simplicity and
  performance. `Money` guards addition with `Math.addExact` and the
  transfer logic rolls back on overflow, but a system that could
  plausibly approach `Long.MAX_VALUE` in aggregate balance would want a
  different representation.
