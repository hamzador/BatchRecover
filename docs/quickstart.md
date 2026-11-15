# Quick start

BatchRecover captures terminal processor skips explicitly enrolled in a sequential Spring Batch 6 chunk; correct and replay individual rejected records rather than restarting the successful records. It is not a job restart replacement or a web application.

## 1. Choose storage

Install locally first: `./mvnw -B -ntp -Prelease-preparation clean install` (Java 21; Docker for integration tests). For a one-shot run without Docker, `./mvnw -B -ntp -DskipTests install` installs unverified local artifacts; run the full suite before release.

Add the starter:

```xml
<dependency>
    <groupId>io.github.hamzador</groupId>
    <artifactId>batchrecover-spring-boot-starter</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

```yaml
batchrecover:
  enabled: true
  application-scope: customer-import
  storage:
    type: memory
```

Use memory only for a bounded, single-JVM, one-shot job. No persistence or transaction rollback. For durable JDBC, add `batchrecover-storage-jdbc` at the same version and your own driver, set `storage.type: jdbc` and `storage.dialect: auto`. Apply both versioned migrations explicitly and use the same JdbcTransactionManager for the chunk, repository and handler. See [SQL dialects](sql-dialects.md). Spring Batch metadata schema is separate.

## 2. Enroll one step

Provide a stable occurrence ID (input identity plus record position, not just a mutable business field), a typed PayloadCodec, and a synchronous RecoveryHandler. Register the handler ID and codec together as a `RecoveryHandlerRegistry.Registration<T>` bean, then use the same ID and codec via `BatchRecoverListenerFactory.create`. Attach the returned listener with `.faultTolerant().skip(...).skipLimit(...).skipListener(listener)` on the chunk builder. See [starter](starter.md) and the complete [standalone source](../consumer-examples/README.md). Capture is opt-in; no automatic instrumentation of all steps.

## 3. Review, correct and replay

Inject RecoveryStorage, RejectionService and BatchRecoverWorker. After the original job finishes and the application verifies the rejected record is safe to replay:

```java
var pending = storage.findPending("customer-import", 100);
// Select and authorize a specific rejection; obtain approvedPayload from business review.
var original = storage.findRevisions(selected.id()).getFirst();
var corrected = rejectionService.correct(selected.id(), selected.version(),
        approvedPayload, "operator", "approved correction");
rejectionService.queue(corrected.id(), corrected.version(), "operator", "approved replay");
var result = worker.runOnce();
```

This fragment uses application-provided selected and approvedPayload values. The original revision is immutable. If no correction is required, queue the current version directly (validation failure requires a new revision). Handle OptimisticLockException by reloading and reviewing again, never by blindly overwriting. A worker may claim another eligible item in the same application scope; one poll is not a synchronous replay-by-ID API. NO_WORK can mean no *currently eligible* work, not that the queue is empty. Schedule bounded polling outside transactions in the host, with backoff; do not busy-spin. A handler must revalidate identity and business rules before performing the intended effect.

## 4. Try the working examples

[Memory and PostgreSQL consumer applications](../consumer-examples/README.md) run a real job and targeted replay from locally installed JARs. They require no source dependency on the library reactor.

## Troubleshooting

| Symptom | Check |
| --- | --- |
| No BatchRecover beans | enabled=true; starter on classpath; application-scope set |
| Missing JDBC repository | Add the optional JDBC adapter, driver and unambiguous JdbcTransactionManager; memory is never a fallback |
| Unknown dialect | Verify product/version via JDBC metadata; explicit dialect must match; no generic unsafe fallback |
| Table not found | Apply V1/V2 for selected dialect; do not mix migration histories |
| No captured rejection | Explicit skip listener, terminal processor skip, compatible chunk transaction |
| CorrectionRequiredException | Supply a newly approved revision, then queue its returned version |
| STALE_CLAIM / NO_WORK | No callback executed for that claim; allow host scheduling and lease recovery |
| StorageCapacityExceededException | Memory capacity includes terminal records and all revisions; choose limits or durable storage, never silently discard |
| Handler fails | Check sanitized outcome and authorized diagnostics; JDBC rolls back only same-transaction effects |

See [storage capabilities](storage.md), [worker semantics](recovery-engine.md), [JDBC migrations and extensions](sql-dialects.md), and [API guide](api.md). No REST, Angular, mandatory database, bundled driver or automatic scheduler.
