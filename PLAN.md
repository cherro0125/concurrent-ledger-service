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

### 3. Idempotency mechanism — done
- `ConcurrentHashMap<String, CompletableFuture<TransferResult>>` keyed by idempotency key, claimed via `putIfAbsent` (not `computeIfAbsent` — the real transfer work, and the eviction-on-failure, both need to happen outside the map's remapping function, which `ConcurrentHashMap`'s Javadoc requires to be quick and not touch the map itself)
- First request claims the key and executes; a concurrent retry with the same key gets the same `Future` and waits on it
- A failed original (exception *or* error) evicts the key so a later retry gets a fresh attempt instead of hanging or permanently replaying a transient failure; a business outcome (success, insufficient funds, account not found) is cached forever
- Extra, beyond the original spec: a key reused with different transfer parameters is rejected (`IdempotencyKeyConflictException`) instead of silently replaying an unrelated result
- Tested with a hand-written `AccountRepository` fake that blocks mid-lookup via a `CountDownLatch`, proving the wait-on-in-flight-original path deterministically, plus a simulated `Error` proving nothing can leak a permanently-stuck future

### 4. Concurrency tests — done
- **Test 1:** 32 threads × 2,000 transfers each (64,000 total) across a shared pool of 8 accounts, `CyclicBarrier`-synchronized start → assert total balance sum unchanged, no negative balances, and `InsufficientFunds` actually occurred (proof the run was contentious, not just parallel-but-disjoint)
- **Test 2:** idempotency race — 16 threads, same idempotency key, synchronized start via `CyclicBarrier` → exactly one execution (verified via a lookup-counting fake, not just matching balances)
- Verified thread count / account pool size actually forces contention: first pass used an initial balance too large relative to transfer size, so `InsufficientFunds` never actually triggered despite the test passing — retuned until it reliably does
- `awaitTermination` with a bounded timeout doubles as deadlock detection: a broken lock ordering shows up as this test failing on a timeout, not hanging silently
- Each thread guarantees a distinct `to` account by construction rather than skipping self-transfers, so the transfer count is exact, not approximate

### 5. HTTP layer — done
- `POST /accounts`, `POST /transfers` (with `Idempotency-Key` header), `GET /accounts/{id}/balance`
- Map `TransferResult` to HTTP status via an exhaustive `switch` (Success → 200, InsufficientFunds → 409, AccountNotFound → 404); `IllegalArgumentException` → 400; `IdempotencyKeyConflictException` → 409
- `LedgerConfiguration` is the one place Spring wiring touches the plain-Java core (`@Bean` methods only, no annotations on `core`/`store`)
- Spring Boot 4 ships Jackson 3, whose base package moved to `tools.jackson.databind` (and `AutoConfigureMockMvc` moved under `org.springframework.boot.webmvc.test.autoconfigure`) — found by letting the compiler fail and inspecting the actual jars, since this is undocumented for a brand-new major version
- Manual `curl` testing against a running instance caught a real bug the test suite didn't: `POST /accounts` with `{}` 400'd because Jackson can't bind a missing JSON field to a primitive `long`; fixed by making `CreateAccountRequest`'s field a boxed `Long`
- Reworked core's input-validation `Objects.requireNonNull` calls (`AccountId`, `TransferService.transfer`, `IdempotentTransferService.transfer`) to throw `IllegalArgumentException` instead, keeping `Objects.requireNonNull` only for constructor/wiring invariants — so `ApiExceptionHandler` no longer catches `NullPointerException` at all, and a genuine future null-dereference bug surfaces as 500, not a misleading 400

### 6. Unhappy-path and integration tests — done
- Insufficient funds, non-existent account (both sides), self-transfer, zero/negative amount, missing idempotency header, idempotency key reused with different parameters, unknown-account balance lookup, negative initial balance — each proven at the actual HTTP boundary (status code, and message where it adds signal) in `LedgerApiErrorHandlingTest`, not just at the core level
- `@SpringBootTest` reuses the same context (and so the same singleton in-memory store) across every test method across both API test classes; idempotency keys are generated per-call (`UUID.randomUUID()`) rather than hardcoded literals, so tests can't silently collide with each other

### 7. README
- Single startup command, API guarantees, examples
- Trade-offs section: no persistence (by requirement), no TTL on idempotency keys (would need cleanup in production), simplified ledger model without full transaction history

### 8. Final review
- Read through the entire `core` package line by line
- Be ready to explain: why this lock ordering, why this idempotency mechanism, what would break without lock ordering (deadlock scenario)
