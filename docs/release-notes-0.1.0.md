# BatchRecover 0.1.0 — draft release notes (not yet released)

Capture. Correct. Replay.

First candidate for item-level recovery of sequential Spring Batch processor skips. Explicitly enroll a step, capture rejected input, review and correct a selected payload, then replay only eligible rejected records. Successful original rows are not rerun.

## Highlights
- Java 21, Spring Boot 4.0.9 and Spring Batch 6.0.6 verified.
- Pure Java domain and optional bounded volatile memory storage.
- JDBC storage using the host driver/DataSource/transaction manager, real tests on PostgreSQL 16, MySQL 8.4, Oracle Free 23 and H2 2.4 native.
- Explicit codecs, stable occurrence identity, immutable revision and audit history, optimistic concurrency, fenced claims and capped jittered retry.
- Offline, versioned DDL generation and extensible JDBC dialects; no automatic migrations.
- Opt-in starter and Micrometer outcomes; no web server, ORM, bundled database drivers or scheduler.
- Independent CSV applications for both memory and PostgreSQL; [quick start](quickstart.md), [API guide](api.md), [SQL dialects](sql-dialects.md) and verified quality reports.

## Artifacts planned for publication
The parent POM plus core, storage-memory, storage-jdbc, spring-batch and spring-boot-starter, each library with binary, sources and Javadoc. No examples, coverage, benchmark or contract-test JAR published in this first release. Coordinates will be io.github.hamzador at version 0.1.0 once authorized. Until then install the repository locally at 0.1.0-SNAPSHOT.

## Limits and operational requirements
Memory is bounded and volatile; it does not join chunk transactions or roll back handler effects. JDBC guarantees require the same physical transaction for the host chunk and local replay writes. No guarantee for remote effects, read/write skips, remote/partitioned steps, cross-database atomicity, older Spring Batch versions, or SQL engines outside the tested matrix. Host owns authorization, data retention, scheduling, safe business validation and compatible migrations.

No tag, official signature, upload or release date has been approved. See [release checklist](release-checklist.md) and [review](release-review.md).
