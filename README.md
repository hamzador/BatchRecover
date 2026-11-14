# BatchRecover

**Capture. Correct. Replay.**

BatchRecover is a proposed Java library for durable, item-level recovery of rejected Spring Batch records. It complements Spring Batch rather than replacing its retry, skip, or job restart mechanisms.

> Status: design / pre-alpha. Core and processor-skip capture are under development; no replay engine or published Maven artifact yet. API examples describe intended behavior, not supported functionality.

## Why?

A batch may complete with skipped records. Restarting a failed job and recovering a rejected record are different operations. BatchRecover aims to capture explicitly configured skips, retain a safe replay payload, and let an application recover selected records without rerunning successful ones.

## First release (0.1.0)

- Java 21, Maven multi-module, Spring Boot 4.0.x / Spring Batch 6.0.x (exact patches to be pinned and verified).
- PostgreSQL / Spring JDBC; capture processor skips on explicitly opted-in chunk steps.
- Durable rejection identity, immutable original payload, explicit correction and revision history.
- Explicit, application-owned typed replay handlers; retry limits, lease-based claims and audit trail.
- Local Java API first. REST and Angular dashboard follow once recovery semantics are proven.

Read and write skips, distributed steps, cross-database transactions, external side effects and compatibility with Spring Batch 5.2 are separate follow-up milestones. Payload capture is opt-in. No claim of exactly-once external effects.

## Documentation

- [Architecture and technical specification](docs/architecture.md)
- [Roadmap, acceptance criteria and test strategy](docs/roadmap.md)
- [Core and PostgreSQL persistence](docs/core-persistence.md)
- [Spring Batch adapter and example](docs/spring-batch-adapter.md)

## Proposed usage

```java
// Illustrative only — API not yet implemented.
@Bean
Step customerImport(JobRepository jobRepository,
                    PlatformTransactionManager transactionManager,
                    SkipListener<CustomerInput, CustomerOutput> recoveryListener) {
    return new StepBuilder("customerImport", jobRepository)
            .<CustomerInput, CustomerOutput>chunk(100)
            .transactionManager(transactionManager)
            .reader(customerReader())
            .processor(customerProcessor())
            .writer(customerWriter())
            .faultTolerant()
            .skip(InvalidCustomerException.class)
            .skipLimit(100)
            .skipListener(recoveryListener)
            .build();
}
```

The application supplies a stable source record identity, a safe versioned payload codec, and a handler that revalidates and applies the intended business operation. Spring Batch remains responsible for original skip decisions.

## Development

Build tooling is planned as Maven, JUnit Jupiter 6, Mockito, Testcontainers PostgreSQL and GitHub Actions. Run `./mvnw test` for unit tests or `./mvnw -B clean verify` with Docker for the full reactor and integration smoke test. See [development setup](docs/development.md).

The proposed Maven namespace is `io.github.hamzador` (ownership and publication still to be verified). Apache-2.0 is a proposed license; no license has been adopted yet.

## References

- [Spring Batch skip and retry](https://docs.spring.io/spring-batch/reference/step/chunk-oriented-processing/configuring-skip.html)
- [Spring Batch 6 migration guide](https://github.com/spring-projects/spring-batch/wiki/Spring-Batch-6.0-Migration-Guide)
- [PostgreSQL row locking](https://www.postgresql.org/docs/current/sql-select.html)

Contributions and design feedback are welcome once contribution terms are established.
