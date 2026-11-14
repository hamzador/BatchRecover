# Changelog

## Unreleased — 0.1.0 target

- Capture sequential Spring Batch 6 processor skips in the original JDBC chunk transaction.
- Store immutable original payload and correction history, versioned rejection state and audit events in PostgreSQL.
- Explicit typed handlers, scoped concurrent workers, leases, fencing, attempt history, capped jittered retry and local transactional replay.
- Bounded single-JVM memory storage, optional JDBC, common storage capabilities and shared contract tests.
- Opt-in Spring Boot starter, typed properties, listener factory, host-driven Micrometer worker facade.
- Customer import and targeted recovery demonstration; tests for rollback, restart, concurrency and recovery.
- JaCoCo reports, CycloneDX SBOM, OSV dependency audit and local unsigned staging.

Not published. Read/write skips, external effects, remote/partitioned steps and older Spring Batch compatibility are outside this release.
