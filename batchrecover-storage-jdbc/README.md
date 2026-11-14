# JDBC adapter

PostgreSQL 16 implementation of the rejection repository. Apply the versioned SQL at
`classpath:db/batchrecover/V1__create_batchrecover_tables.sql`
once through the host application's migration system (e.g. a dedicated Flyway configuration and history table).
No automatic migration or schema changes are performed by the starter. Do not rerun V1 on an existing installation.

```java
var transactionManager = new JdbcTransactionManager(dataSource);
var repository = new JdbcRejectionRepository(dataSource, transactionManager);
var service = new RejectionService(repository, Clock.systemUTC());
```

Use the same DataSource instance and JDBC transaction manager as the enclosing chunk. Repository transactions use REQUIRED propagation and assume PostgreSQL READ COMMITTED for duplicate capture. Retry serialization errors at the outer transaction boundary. The actual Spring Batch listener integration is M2.

The stored payload must already be approved by the host application. Encoded text is stored unchanged (maximum 64 KiB UTF-8); duplicate detection compares the exact original encoding, schema version, type and handler, not corrected content or execution IDs. Use deterministic codecs. The host owns authorization, encryption/retention and semantic validation; no automatic secret detection is provided.

Capture, correction, queue and discard are implemented. Attempt persistence operations, worker claims, replay, lease processing and jitter are M3; the table and domain types reserve the model but are not operational. No REST or automatic Spring bean wiring yet.
