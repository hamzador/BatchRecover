# Architecture and technical specification

Status: proposal, not implemented. Version target: 0.1.0.

## 1. Goals and non-goals

Capture terminal, explicitly configured processor skips in a single-process Spring Batch job; store a versioned safe payload; allow correction and targeted replay via a registered handler. Original Spring Batch job status and counts remain unchanged. Do not replay filtered items (processor returns null), transient failures that later succeed, or unskipped fatal failures. Do not dynamically load arbitrary Java types from stored JSON.

MVP supports Java 21, Spring Boot 4.0.x, Spring Batch 6.0.x, PostgreSQL and JDBC; exact versions are build decisions. No REST exposure, dashboard, automatic listener weaving, partitioned/remote steps, read or write replay, or cross-database atomicity in the first slice. Spring Batch 5.2 support can be a separate adapter. 
   
## 2. Module boundaries

```mermaid
flowchart TD
  A[Spring Batch processor skip] --> B[Opt-in capture adapter]
  B --> C[Core rejection service]
  C --> D[Repository port]
  D --> E[PostgreSQL JDBC adapter]
  F[Local Java recovery API] --> G[Recovery coordinator]
  G --> D
  G --> H[Application replay handler]
  I[Boot starter] -. wires .-> B
  I -. wires .-> G
```

- batchrecover-core: domain records, state machine, ports, policies; no Spring dependency.
- batchrecover-storage-jdbc: PostgreSQL repository, SQL schema, transactions and locking.
- batchrecover-spring-batch: opt-in SkipListener adapter and step-context mapping.
- batchrecover-spring-boot-starter: conditional bean configuration and property validation; no job launch or schema changes by default.
- batchrecover-examples: runnable sample with one customer import and one replay handler.
- Later: batchrecover-rest and batchrecover-dashboard (Angular).

## 3. Capture semantics

Register a typed SkipListener on each selected step. Capture onSkipInProcess only. Retryable errors and filtered records do not create rejection records. Resolve job instance, job execution, step execution and configured step name. The application must provide a stable source record occurrence ID within the job instance (a business key alone may collide, e.g. repeated CSV rows; include source identity and offset). No identity inferred from object hash or mutable payload.

Persist with the same DataSource and transaction manager as the original chunk; reject unsupported configurations. Capture must participate in the chunk transaction, not REQUIRES_NEW. If the chunk rolls back, the rejection rolls back. On a later successful retry/restart, capture can occur again. Enforce uniqueness in PostgreSQL on application scope + job instance + logical step + source occurrence + phase; store execution IDs as provenance, not identity. Insert idempotently, never overwrite original data on duplicate; unexpected content mismatch must surface as an invariant failure. Fail the step if capture cannot be persisted (fail closed), rather than silently losing a skip. Verify actual callback behavior against the pinned Spring Batch version with integration tests; do not assume identical semantics across 5.x and 6.x.

Read callbacks provide a Throwable, not necessarily the source payload. Write callbacks receive the output rather than the original input; moreover Spring Batch 6 scanning can roll back the callback transaction. Both require a dedicated design, version-specific tests and an explicit payload/commit strategy before enabling replay. Do not advertise them as supported.

## 4. Proposed Java interfaces

Illustrative only; names and signatures may change before the first release.

```java
public interface RecordIdentity<T> {
    String sourceOccurrenceId(T item);
}

public interface PayloadCodec<T> {
    String typeId();
    int schemaVersion();
    String encode(T item);
    T decode(String payload, int version);
}

public interface RecoveryHandler<T> {
    void validate(T item);
    void replay(T item, RecoveryContext context);
}
```

RecoveryContext exposes rejection ID, stable operation idempotency key and attempt metadata. Register handler IDs and codecs explicitly; no reflection-driven arbitrary execution. Validate corrected payload server-side and again on replay. Handler owns business semantics; do not call a generic original ItemWriter automatically. The example uses a transactional JDBC upsert, not an external API.

## 5. Persistence model (logical)

| Table | Key data |
| --- | --- |
| br_rejection | UUID, app scope, job instance/execution IDs, step execution ID/name, source occurrence ID, phase, error code/category, handler ID, payload type/schema version, status, attempt count, max attempts, next attempt at, lease token/expiry, version, timestamps |
| br_payload_revision | rejection ID + revision number, safe immutable payload, schema version, changed by, reason, created at |
| br_recovery_attempt | attempt UUID, rejection ID, revision, lease/fencing token, start/end time, outcome, sanitized error |
| br_audit_event | event UUID, rejection ID, action, actor, reason, prior/new status, timestamp |

Unique rejection identity as above. Payload revision 0 is original. Correction appends a new revision; it never modifies revision 0. Foreign keys, check constraints for states and nonnegative counters, indexes on status/next attempt and lease expiry. Timestamps UTC. No foreign key to Spring Batch metadata (retention can differ). Use namespaced explicit versioned SQL migrations, applied by the consuming application before enabling the feature. Payload retention must cover all revisions and backups through the host's policy.

## 6. State machine

```mermaid
stateDiagram-v2
  [*] --> PENDING
  PENDING --> PENDING: correction
  PENDING --> QUEUED: request replay
  QUEUED --> RUNNING: claim
  RUNNING --> RECOVERED: committed success
  RUNNING --> QUEUED: retryable failure or expired lease below limit
  RUNNING --> DEAD: exhausted attempts
  RUNNING --> PENDING: business validation failure below limit
  PENDING --> DISCARDED: authorized discard
  QUEUED --> DISCARDED: authorized discard
  RECOVERED --> [*]
  DEAD --> [*]
  DISCARDED --> [*]
```

Correction and replay requests require optimistic version checks; never correct an active queued/running record. Queueing is explicit. Retryable errors use bounded exponential backoff with jitter. Business validation failures require correction. All unsuccessful claims consume a budget; exhausted attempts become DEAD, not silently reset. DEAD reset/reopen is a later feature. Status changes and corresponding audit entries commit atomically.

## 7. Concurrency and recovery transactions

1. Short claim transaction: select eligible QUEUED rows ordered by due timestamp and UUID using FOR UPDATE SKIP LOCKED. Update state, lease token, expiry, attempt counter and append attempt/audit; commit. A reaper handles expired RUNNING leases, consuming the already allocated attempt and respecting limits.
2. Per-item execution transaction: lock the rejection by ID, validate state, fencing token and lease at start, select the pinned revision, validate and execute the application-owned handler with the same JDBC transaction. Hold the row lock through execution; configure bounded transaction/statement timeouts. Commit effect, RECOVERED and audit together. Reaper cannot reclaim a locked running row.
3. On handler error roll back execution. In a separate short transaction, check the same token, record sanitized failure, and transition to PENDING, QUEUED or DEAD. If the process crashes before failure recording, the reaper handles expiry. Stale tokens must never finalize newer claims.

The stable idempotency key represents the intended business operation, not an attempt or corrected revision; the handler must enforce business uniqueness. Atomic local effects require the same physical transaction and no autonomous writes. External HTTP calls, multiple databases and asynchronous side effects are out of MVP: an outbox plus downstream idempotency may be added later. No universal exactly-once claim.

## 8. Security and observability

Disabled by default; explicit step enrollment and payload allowlist, size limit and sensitive-field handling. The host supplies a replayable approved payload or a durable safe reference; masking alone may make replay impossible. Never persist arbitrary exception stack traces, secrets, raw credentials or unsanitized messages. Disable unsafe polymorphic JSON deserialization. Actor and authorization are application-owned in local API; future REST must enforce authentication, roles, scoped access, audit and optimistic concurrency. Avoid high-cardinality metric labels and never include payloads in logs. Proposed counters: captured, duplicate capture, attempted, recovered, dead; timers for replay and gauge for backlog. Retention and access controls required before production use.

## 9. Compatibility and decision records

- ADR-001: use Spring Batch 6 / Boot 4 for a new project; 5.2 adapter later. The 5.2 OSS release line is approaching/end of maintenance per official release announcement.
- ADR-002: capture processor skips only in the first release; prove rollback safety before adding other phases.
- ADR-003: JDBC and PostgreSQL first, favor explicit transaction semantics over ORM abstractions.
- ADR-004: replay handler is explicit and registered; no arbitrary job restart.
- ADR-005: Java API first, optional REST/dashboard later. 
- ADR-006: Apache-2.0 and io.github.hamzador are proposals pending owner decision and namespace verification.

## References

- [Spring Batch 6 migration guide](https://github.com/spring-projects/spring-batch/wiki/Spring-Batch-6.0-Migration-Guide)
- [Spring Batch skip configuration](https://docs.spring.io/spring-batch/reference/step/chunk-oriented-processing/configuring-skip.html)
- [Spring Batch 6.0.4 and 5.2.6 announcement](https://spring.io/blog/2026/06/10/spring-batch-6-0-4-and-5-2-6-available-now/)
- [PostgreSQL locking clause](https://www.postgresql.org/docs/current/sql-select.html)
