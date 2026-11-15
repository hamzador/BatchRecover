# Storage architecture (Sprint 6)

BatchRecover Core remains Java-only. The existing RejectionRepository and RecoveryRepository ports stay in core; RecoveryStorage combines both and declares capabilities. No separate SPI module, ORM or compulsory JDBC driver. The starter includes the tiny memory adapter; JDBC must be added explicitly as a dependency. The Spring Batch adapter continues to depend on the existing ports, not storage implementations.

| Guarantee | Memory | JDBC (same physical transaction) |
| --- | --- | --- |
| Retention after JVM exit | No | Yes |
| Multiple threads per storage | Yes | Yes |
| Multiple JVMs | No | Yes |
| Atomic metadata transitions | Yes | Yes |
| Capture joins chunk transaction | No | Yes (same JDBC transaction) |
| Rollback of handler effects | No | Yes (same JDBC transaction) |

Memory mode is opt-in and intended for one-shot/nontransactional batches and synchronous idempotent handlers. A callback can be observed before a chunk fails; a memory rejection may remain even if the chunk later rolls back, and a handler may leave partial effects on failure. Memory mode is not a substitute for durable transaction-aware recovery. Do not queue or replay until the original job has finished and the application has verified the records are safe to replay. ResourcelessJobRepository in Spring Batch 6 is intended for one-time jobs; do not reuse the same input identity namespace across independent jobs whose repository IDs are reused. Supply a unique stable input occurrence prefix, or create a fresh storage per one-shot job.

## Configuration

```yaml
batchrecover:
  enabled: true
  application-scope: customer-import
  storage:
    type: memory
    max-records: 1000
    max-revisions-per-record: 8
    max-payload-bytes: 16777216
```

Limits cover all records (including terminal records) and the encoded UTF-8 bytes of every retained revision, not the precise Java heap size. Attempt counts are bounded by the existing capture budget (max 100); audit growth is bounded by revisions and attempts. No TTL, eviction, or automatic clearing: full storage raises StorageCapacityExceededException and a new capture fails rather than silently dropping rejects. Duplicate captures do not consume capacity. Failed corrections leave the existing state unchanged. Configure limits conservatively and do not keep a memory store indefinitely. The application owns the lifecycle; all history is lost when the JVM or storage instance ends.

Memory scheduling uses an injected Clock, synchronized metadata changes and an execution fence per rejection. Handler callbacks run outside the global lock, and stale/expired claims cannot start. An executing callback is not preempted by expiration; handler effects cannot be rolled back. Use bounded synchronous handlers with stable idempotency keys. Retry after an exception can repeat partial effects.

List pending rejects using RecoveryStorage.findPending(scope, limit) (1–1000). It is a bounded first page, not cursor pagination or a public REST endpoint. Read/modify the selected rejection through RejectionService and use the host-managed worker. The original payload and audit are retained.

## JDBC and custom storage

For existing JDBC users, enabled=true without storage.type still selects JDBC; add the batchrecover-storage-jdbc artifact explicitly. Set storage.type=jdbc for new apps. Missing classes or missing/ambiguous transaction manager fail startup rather than falling back to memory. Existing V1 and V2 migrations and JDBC guarantees are unchanged. Sprint 7 validated PostgreSQL 16, MySQL 8.4, Oracle Free 23 and native H2 2.4 through real-engine CI. See [SQL dialects](sql-dialects.md). Do not mix backing stores for capture and recovery.

Custom providers can implement RecoveryStorage or supply both existing ports as beans and set storage.type=custom. Custom capture is transactional by default (mandatory capture must be implemented); direct Java users may explicitly choose nontransactional CaptureSettings only if they accept the implications. No automatic schema creation, no automatic scheduler.

The shared JUnit contract lives under core test sources, packaged as a test-scoped test JAR. Memory and all four validated JDBC engines run it. H2 embedded mode is not multi-JVM storage. Backend-specific tests verify JDBC rollback and memory bounds. No testing dependency is in the production library.

The library exposes no REST, web server or UI. File/NoSQL providers are deferred until demand warrants maintenance.

The CI resolves an independent consumer POM against the locally installed starter and rejects transitive BatchRecover JDBC adapter, vendor drivers, web/ORM and test-tool dependencies. It also prints actual library JAR sizes. Framework dependencies already required by Spring Batch may still be present; no claim of zero framework dependencies for the starter.
