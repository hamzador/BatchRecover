# JDBC dialects and offline migrations (Sprint 7)

The JDBC adapter remains optional and uses the host DataSource and JdbcTransactionManager. No vendor driver is a production dependency. The target matrix is PostgreSQL 16, MySQL 8.4, Oracle Database Free 23, H2 2.4 (native mode). Support for additional versions/engines is not implied by passing these tests. The actual results of the PR CI determine the verified matrix.

## Configuration

```yaml
batchrecover:
  enabled: true
  application-scope: customer-app
  storage:
    type: jdbc
    dialect: auto
```

Add batchrecover-storage-jdbc explicitly, plus your database driver and Spring Batch setup. Choose auto, postgresql, mysql, oracle, h2, or a registered extension name. Detection uses JDBC DatabaseMetaData on the first repository access, not during starter creation. An explicit name must match the real metadata, including version. Unknown, ambiguous, or unsupported engines fail closed; there is no fallback to memory. A custom SqlDialect bean is discovered by the starter; direct Java users pass a SqlDialectRegistry and requested name into the repository constructor. Extensions own their SQL, bindings, migrations and real backend tests.

## Generate the DDL offline

The schema generator returns ordered immutable V1/V2 scripts. PostgreSQL retains its original migrations byte-for-byte; existing installations do not rerun them. The other engines render a shared versioned portable schema template with appropriate types and table options.

```sh
./mvnw -B -pl batchrecover-storage-jdbc -am package
java -cp batchrecover-storage-jdbc/target/batchrecover-storage-jdbc-0.1.0-SNAPSHOT.jar \
  io.github.hamzador.batchrecover.storage.jdbc.schema.SchemaCli mysql ./generated/mysql
```

The CLI performs no database connection and refuses to overwrite existing migration files. Review the SQL and apply both scripts via the host migration tool using a dedicated history. Use separate migration histories for separate databases. No automatic schema initialization and no cross-engine migration of existing data. Never edit a released migration template in place; append a migration version.

## Portability decisions

| Aspect | PostgreSQL | MySQL, Oracle, H2 |
| --- | --- | --- |
| UUID | native UUID | canonical UUID text |
| Time | timestamptz | signed UTC epoch microseconds |
| Identity uniqueness | original composite unique constraint | full-tuple SHA-256, exact tuple checked on duplicate |
| Payload | text | LONGTEXT (MySQL), CLOB (Oracle/H2) |
| Indexes | partial | full composite |
| Duplicate capture | ON CONFLICT | plain INSERT; rollback only to savepoint on duplicate |
| Current revision constraint | deferred FK | repository enforces in same transaction |

MySQL's index byte limit makes the full 512-character Unicode occurrence and scope tuple unsuitable for a portable unique index. Digest is length-delimited and checked against the exact identity on duplicate; a collision fails closed. Oracle cannot express the portable payload UTF-8 byte bound as a simple CLOB CHECK, so the core's validated ReplayPayload remains authoritative. MySQL cannot defer a cyclic revision FK, so the portable schema excludes it; repository operations maintain both rows atomically. Do not write application state by direct SQL. Use binary/case-sensitive comparisons and Unicode database encoding (MySQL DDL uses utf8mb4_0900_bin, Oracle AL32UTF8 and binary comparison). H2 uses native SQL, not compatibility modes.

## Concurrency and transaction boundaries

All dialects use a bounded window of 64 candidates, then lock each by primary key with FOR UPDATE SKIP LOCKED while rechecking scope, status, due time and attempt budget. Oracle does not allow row limiting with FOR UPDATE in the same query, so this is a common portable strategy. A poll can return no work when the candidate window is contended; the host should poll again with a bounded cadence. Each mutation remains fenced by version and lease token. No broad queue locks or automatic retry of arbitrary handler exceptions at the JDBC layer.

Workers use READ COMMITTED and the database clock, with a Spring transaction timeout of 30 seconds; only PostgreSQL adds transaction-local statement and lock timeouts. Configure appropriate driver/session timeouts for other engines. H2's CURRENT_TIMESTAMP is transaction-stable, so its timing resolution is the short transaction boundary rather than a wall-clock expression. JDBC capture joins the original physical chunk transaction. Use READ COMMITTED for chunk transactions and synchronize host/database clocks. Retry serialization/deadlock failures only at the outer transaction boundary. Business effects must use the same JDBC transaction; no independent remote/external operations. H2 embedded mode is not a distributed deployment; use a shared database server for multi-JVM workers.

## Verification

The existing PostgreSQL integration and Spring Batch rollback/restart tests are retained. The same core contract suite runs against MySQL 8.4 and Oracle Free 23 Testcontainers, native H2 and PostgreSQL 16. The three new adapters additionally test actual chunk transaction participation, recovery rollback, stale/expired leases, large UTF-8 payloads, identity case sensitivity, and durable repository reads across connections. The memory and JDBC consumer dependency checks inspect independently resolved runtime dependency trees.

Do not claim support for older Oracle, MariaDB, SQL Server, SQLite, DB2 or additional databases merely because they have JDBC drivers. Add a verified dialect as needed. No vendor drivers are bundled.
