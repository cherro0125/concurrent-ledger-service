# Concurrent Ledger Service — Project Plan

## Task Summary

Build the core of a wallet product: a double-entry ledger service. Accounts hold money; clients post transfers between accounts concurrently, and sometimes retry.

**Key requirements:**
- HTTP API: create account, post transfer, read balance
- Transfers are atomic — debit + credit, or nothing changes
- Balance must never go negative
- Idempotency key on transfers: repeated key returns original outcome, applied at most once — even if the retry arrives while the original is still in flight
- Correct under heavy concurrent load on the same accounts: no lost updates, no double-spends, under any thread interleaving
- Transfers on unrelated accounts must proceed in parallel
- State in memory only — no database (not even embedded), no transaction/actor libraries for the core
- Ledger core must be plain Java, testable without HTTP
- Tests must demonstrate concurrency guarantees, not just single-threaded happy paths
- README: startup command, API guarantees, trade-offs

See [TECH_STACK.md](./TECH_STACK.md) for the stack and the explicitly-prohibited list, and [CLAUDE.md](./CLAUDE.md) for the enforced project rules.

---

## Tasks

### 0. Project setup — done
- Gradle project + wrapper, Java 21, `spring-boot-starter-webmvc`, JUnit 5 + AssertJ
- Package structure: `core`, `store`, `api`

### 1. Domain model — done
- `AccountId`, `Money` (long in minor units, validated ≥ 0), `Account` (holds its own `ReentrantLock`)
- Sealed interface `TransferResult` with variants: `Success`, `InsufficientFunds`, `AccountNotFound`
- **Review checklist:** `Money` rejects negative values; `Account` doesn't expose mutable state without going through the lock

### 2. Core transfer logic — done
- Lock ordering by `accountId.compareTo()` to prevent deadlocks between concurrent opposite-direction transfers
- Review for race conditions, deadlock risk, edge cases (self-transfer, double-locking same account)
- This is the section to be able to defend line-by-line in a technical review

### 3. Idempotency mechanism
- `ConcurrentHashMap<String, CompletableFuture<TransferResult>>` + `computeIfAbsent`
- First request claims the key and executes; a concurrent retry with the same key gets the same `Future` and waits on it
- Critical: exception handling — a failed original request must not leave the retry waiting forever
- Manually test: fire two concurrent requests with the same key, force an exception in the first, confirm the second doesn't hang

### 4. Concurrency tests
- **Test 1:** N threads × M random transfers across a shared pool of accounts → assert total balance sum unchanged, no negative balances
- **Test 2:** idempotency race — two threads, same idempotency key, synchronized start via `CyclicBarrier` → exactly one execution
- Verify thread count / account pool size actually forces contention (too few accounts = trivially catches bugs; too many = test proves nothing)

### 5. HTTP layer
- `POST /accounts`, `POST /transfers` (with `Idempotency-Key` header), `GET /accounts/{id}/balance`
- Map `TransferResult` to HTTP status: Success → 200, InsufficientFunds → 409, AccountNotFound → 404, invalid input → 400

### 6. Unhappy-path and integration tests
- Insufficient funds, non-existent account, self-transfer, zero/negative amount
- Integration test (`@SpringBootTest`) covering the full flow through HTTP

### 7. README
- Single startup command, API guarantees, examples
- Trade-offs section: no persistence (by requirement), no TTL on idempotency keys (would need cleanup in production), simplified ledger model without full transaction history

### 8. Final review
- Read through the entire `core` package line by line
- Be ready to explain: why this lock ordering, why this idempotency mechanism, what would break without lock ordering (deadlock scenario)
