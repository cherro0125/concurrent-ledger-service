# Project rules

This is a take-home code challenge: a concurrent, in-memory, double-entry ledger service. See [PLAN.md](./PLAN.md) and [TECH_STACK.md](./TECH_STACK.md) for the full task description and plan.

## Hard constraints — do not violate

These come directly from the challenge requirements. Do not introduce any of the following into the `core` ledger logic (or anywhere in the service) under any circumstances, even if it would make a task easier or a test pass faster:

- **No database of any kind** — no embedded database (H2, SQLite, RocksDB, Derby, ...) and no external one. State lives in memory only, in plain Java data structures.
- **No JPA / Hibernate**, no `spring-boot-starter-data-jpa`, no ORM of any kind.
- **No `@Transactional`** or any other declarative/managed transaction mechanism.
- **No actor libraries** (e.g. Akka) and no transaction libraries for the core ledger logic.
- **No resilience/retry libraries** (e.g. Resilience4j) as a substitute for the idempotency mechanism — idempotency must be implemented directly with `java.util.concurrent` primitives.

If a task seems to require one of the above, stop and flag it instead of adding the dependency — it means the approach needs to change, not the rule.

## What's expected instead

- Concurrency correctness via `java.util.concurrent` primitives (`ReentrantLock`, `ConcurrentHashMap`, `CompletableFuture`, `ExecutorService`, `CyclicBarrier`, atomics) — see [TECH_STACK.md](./TECH_STACK.md).
- The ledger core (`core` package) must be plain Java, with no framework dependency, and testable without HTTP.
- Code should be structured so a real storage engine could later replace the in-memory store (i.e. keep persistence behind an interface).
