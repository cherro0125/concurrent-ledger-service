# Tech Stack

| Layer | Choice |
|---|---|
| Language | Java 21 |
| Build tool | Gradle + wrapper (`./gradlew`) |
| HTTP | Spring Boot 4 — `spring-boot-starter-webmvc` only (Boot 4's rename of `spring-boot-starter-web`) |
| Concurrency | `java.util.concurrent` (`ReentrantLock`, `ConcurrentHashMap`, `CompletableFuture`, `ExecutorService`, `CyclicBarrier`) |
| Testing | JUnit 5 + AssertJ |

## Explicitly avoid

These are excluded by the challenge requirements ("no database, embedded or otherwise, and no transaction or actor libraries for the core"):

- Any database, embedded or not (H2, SQLite, RocksDB, etc.)
- JPA / Hibernate, `spring-boot-starter-data-jpa`
- `@Transactional` or any declarative transaction management
- Actor libraries (e.g. Akka)
- Resilience/retry libraries (e.g. Resilience4j) used as a substitute for the idempotency mechanism

See [CLAUDE.md](./CLAUDE.md) for the enforced version of this rule.
