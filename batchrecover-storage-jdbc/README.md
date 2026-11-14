# JDBC adapter

Optional PostgreSQL 16 implementation of the storage ports. Add this artifact explicitly alongside the starter for JDBC mode. Apply the versioned SQL at
`classpath:db/batchrecover/V1__create_batchrecover_tables.sql`
once through the host application's migration system (e.g. a dedicated Flyway configuration and history table).
No automatic migration or schema changes are performed by the starter. Do not rerun V1 on an existing installation.

```java
var transactionManager = new JdbcTransactionManager(dataSource);
var repository = new JdbcRejectionRepository(dataSource, transactionManager);
var service = new RejectionService(repository, Clock.systemUTC());
```

Use the same DataSource instance and JDBC transaction manager as the enclosing chunk. Repository transactions use REQUIRED propagation and assume PostgreSQL READ COMMITTED for duplicate capture. Retry serialization errors at the outer transaction boundary. The Spring Batch processor-skip adapter defaults to mandatory transactional capture.

The stored payload must already be approved by the host application. Encoded text is stored unchanged (maximum 64 KiB UTF-8); duplicate detection compares the exact original encoding, schema version, type and handler, not corrected content or execution IDs. Use deterministic codecs. The host owns authorization, encryption/retention and semantic validation; no automatic secret detection is provided.

Capture, correction, queue, discard and local JDBC recovery are implemented. Apply V2 (scoped worker indexes) after V1 using the host migration system. Worker calls own independent transactions; same-DataSource synchronous handler effects commit with the recovery status. See [recovery engine](../docs/recovery-engine.md). No server/UI required. Optional Spring bean wiring is available via the [starter](../docs/starter.md).
