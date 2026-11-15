# M3 — Recovery engine

## Local worker API

Register typed handlers explicitly with their payload codecs. A worker is scoped to one application. Run outside any ambient transaction; callers own scheduling, concurrency limits, lifecycle and exception monitoring.

```java
var registry = new RecoveryHandlerRegistry(List.of(
    new RecoveryHandlerRegistry.Registration<>("customer-import", codec, handler)));
var engine = new RecoveryEngine(jdbcRepository, registry,
    new RecoverySettings("customer-app", Duration.ofSeconds(30),
        new RetryPolicy(Duration.ofSeconds(1), Duration.ofMinutes(1))));
RecoveryResult result = engine.runOnce();
```

`runOnce()` reaps at most one expired claim then claims and executes at most one due rejection; host may call `reapOnce()` in a separate maintenance loop. Poll at a bounded rate, including NO_WORK and STALE_CLAIM; there is no automatic scheduler or unbounded thread pool. Results: NO_WORK, RECOVERED, STALE_CLAIM, RETRY_SCHEDULED, CORRECTION_REQUIRED, DEAD. Infrastructure/commit errors propagate rather than being treated as business failures; monitor them.

## Transactions and fencing

1. Claim: scoped QUEUED scan using dialect-specific FOR UPDATE SKIP LOCKED, ordered by due timestamp and UUID; allocate a random lease token and increment attempt count; insert a RUNNING attempt and audit; commit.
2. Execute: lock the exact rejection with the claim token, scope, attempt and revision; check lease against database clock. Load the pinned payload revision. Decode, validate and replay synchronously in the same JDBC transaction, including success status, attempt finalization and audit. The lock excludes the reaper until execution commits or rolls back.
3. On a handler exception, execution rolls back; a new transaction finalizes the failure if the token still matches. Validation failures return to PENDING until budget is exhausted; other failures schedule a retry. On an infrastructure error or crash, the reaper recovers the expired claim once the lock is released. An expired or stale token cannot initiate another execution; finalization within an already locked execution may complete after the lease deadline.
4. Reaper: scoped expired RUNNING scan with SKIP LOCKED; finalize attempt and switch to QUEUED or DEAD. The original allocated attempt consumes budget. No resetting attempts on correction. Attempts are append-on-claim, finalized once; original revisions and audit are never overwritten.

The database clock governs due and expiration decisions; synchronize host clocks for capture/queue timestamps. Worker transaction timeout is 30 seconds, PostgreSQL statement timeout 30 seconds, lock timeout 5 seconds during callback. These do not forcibly interrupt arbitrary handler CPU or blocking I/O. Use short, bounded synchronous handlers, a correctly configured JDBC driver/connection timeouts and operational monitoring. A hung session holding a row lock cannot be reclaimed until the database releases it.

## Retry

Capped exponential delay (initial × 2^(attempt−1), limited by maximum) with equal jitter uniformly selected between half the capped delay (rounded up) and the cap in milliseconds. Failure codes are fixed sanitized enums, not raw exception messages. Missing handler or incompatible codec is a bounded retry; explicit RecoveryValidationException requires correction (or DEAD at the limit). After a validation failure, the JDBC repository refuses queueing the same revision, even if attempts remain. An explicit new revision is required, and the corrected item is revalidated before replay.

## Idempotence contract

RecoveryContext.idempotencyKey is the stable rejection UUID (not the lease token or payload revision). Rejection identity is stable across restarts within the same job instance. Local exactly-once effects are conditional on the application handler using the **same DataSource and physical JDBC transaction** with a business uniqueness constraint; no independent commits, REQUIRES_NEW, asynchronous work, other databases, external HTTP or messaging. The library cannot detect arbitrary violations of that handler contract. A separate outbox/remote idempotency design is required for external effects. The sample uses source_row as a unique key, verifies an existing row matches, and never overwrites an already-successful record.

## Migration and demo

Apply V1 then V2 (scoped worker indexes) using your migration history; existing V1 deployments apply V2 only. No automatic migrations. The demo can run with --init --recover-three on a fresh disposable PostgreSQL database; it imports 90 of 100 customers, corrects 3 of 10 rejected records and replays only those 3 (93 customers, 7 pending). The original Spring Batch execution stays unchanged. See the examples README.

## Quality gates

Testcontainers tests cover rollback after handler write and failed success audit, competing workers, isolated application scopes, expired and stale tokens, attempt exhaustion, correction/revalidation, and distinct stable local effects. CI must pass before merge. No production certification.

## Storage capabilities

The transaction and rollback guarantees above apply to JDBC only. The optional single-JVM memory adapter provides fenced metadata transitions but cannot roll back handler effects. Memory handlers must be idempotent and bounded; see [storage architecture](storage.md).
