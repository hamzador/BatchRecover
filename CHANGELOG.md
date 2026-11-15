# Changelog

## 0.1.0 — prepared, not yet published

- Capture sequential Spring Batch 6 processor skips in the original JDBC chunk transaction.
- Store immutable original payload and correction history, versioned rejection state and audit events in memory or durable JDBC storage, according to the selected provider.
- Explicit typed handlers, scoped concurrent workers, leases, fencing, attempt history, capped jittered retry and local transactional replay.
- Extensible JDBC dialect registry, offline SQL generation and real-engine integration tests (PostgreSQL 16, MySQL 8.4, Oracle Free 23, native H2 2.4).
- Bounded single-JVM memory storage, optional JDBC, common storage capabilities and shared contract tests.
- Opt-in Spring Boot starter, typed properties, listener factory, host-driven Micrometer worker facade.
- Customer import and targeted recovery demonstration; tests for rollback, restart, concurrency and recovery.
- JaCoCo strict gates, PIT, SpotBugs, isolated JMH, CycloneDX SBOM and blocking OSV dependency audit.
- Independent Spring Boot consumers importing CSV with and without a database, quick start and API guides.
- Offline non-SNAPSHOT build and signing rehearsal; malformed Unicode metadata rejected consistently with payload validation.

See [0.1.0 release notes](docs/release-notes-0.1.0.md).

Not published. Read/write skips, external effects, remote/partitioned steps and older Spring Batch compatibility are outside this release.
