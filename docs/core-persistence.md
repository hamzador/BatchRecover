# Core and persistence contracts

The current implementation provides a framework-independent domain and local Java service.
Capture creates an immutable original revision and one audit event in the same transaction as the rejection.
The database unique constraint resolves concurrent capture; duplicates return the existing snapshot without overwriting original payload or execution provenance. Conflicting original payload or handler fails closed. PostgreSQL READ COMMITTED is the supported isolation for duplicate resolution.

Corrections require PENDING status, the same payload type and an expected version. They append a revision and audit; schema version may evolve. Queue and discard are explicit, versioned operations. The repository enforces compare-and-set independently of the service's pre-check. A database failure rolls back all changes; outer same-DataSource JDBC transactions are joined (REQUIRED), not suspended. Payload revision foreign key is deferred to commit.

The transition graph and worker transitions are implemented; see [recovery engine](recovery-engine.md). RetryPolicy supports bounded equal jitter. Host callers must sanitize actor/reason/error codes, approve payloads, enforce authorization and validate business content. Payload size/UTF-8 validity and type consistency are checked, but this is not a secret scanner.

## Migration

For PostgreSQL apply `db/batchrecover/V1__create_batchrecover_tables.sql` and `db/batchrecover/V2__scope_worker_indexes.sql` explicitly, using a dedicated migration history to avoid collisions with host migrations. PostgreSQL 16 is the tested original schema. MySQL 8.4, Oracle Free 23 and H2 2.4 use generated migrations; see [SQL dialects](sql-dialects.md). No automatic schema initialization. A fresh database and transaction rollback are exercised by Testcontainers.

## Testing

`./mvnw test` runs unit tests; `./mvnw -B clean verify` also runs JDBC integration tests on all four databases (Docker required). CI is the acceptance gate. See roadmap for remaining milestones.

## API example

```java
var request = new CaptureRequest(
    new RejectionIdentity("customer-app", 1L, "import", "file:42"),
    2L, 3L, "customer", "INVALID_EMAIL", ErrorCategory.BUSINESS,
    new ReplayPayload("customer", 1, "{\"email\":\"invalid\"}"), 3);
var rejection = service.capture(request);
var corrected = service.correct(rejection.id(), rejection.version(),
    new ReplayPayload("customer", 1, "{\"email\":\"user@example.org\"}"),
    "operator", "correct email");
service.queue(corrected.id(), corrected.version(), "operator", "request replay");
```

Queueing alone does not execute the handler; call the recovery engine from a host-managed worker.

For nontransactional memory storage and capability differences, see [storage](storage.md).
