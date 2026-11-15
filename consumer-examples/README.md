# Standalone Spring Boot consumers

These two applications are **outside the library Maven reactor**, have their own Spring Boot 4.0.9 parent, and resolve BatchRecover through installed artifacts. They use real Boot auto-configuration discovery, explicit listener registration, a real Spring Batch job and a typed recovery handler. Neither is a release artifact. The demo applications retain their own 1.0.0-SNAPSHOT versions.

From the 0.1.0 repository checkout, install the unpublished library first (Java 21, Maven wrapper, Docker for JDBC integration):
```sh
./mvnw -B -ntp -Prelease-preparation clean install
```

## Memory: no database
```sh
./mvnw -B -ntp -f consumer-examples/memory/pom.xml clean verify
java -jar consumer-examples/memory/target/batchrecover-memory-consumer-1.0.0-SNAPSHOT.jar
```
Only the BatchRecover starter is required. No DataSource or BatchRecover JDBC adapter is present. This one-shot sample uses ResourcelessJobRepository and ResourcelessTransactionManager. All recovery history is lost when the process exits. Never use memory mode as a substitute for transactional durable capture.

## PostgreSQL: durable storage
```sh
./mvnw -B -ntp -f consumer-examples/jdbc/pom.xml clean verify
```
The test starts a real disposable PostgreSQL 16 container. To run manually, create an empty disposable database, then export DEMO_JDBC_URL, DEMO_DB_USER and DEMO_DB_PASSWORD (do not commit credentials).
```sh
java -jar consumer-examples/jdbc/target/batchrecover-jdbc-consumer-1.0.0-SNAPSHOT.jar --demo.initialize=true
```
The explicit initialization flag creates Spring Batch metadata, BatchRecover V1/V2 and the demo table. Never run this flag against a real application database. Use a fresh empty database for every sample run. The real integration test exercises the same application entry points. The host supplies the driver and the same JdbcTransactionManager for the chunk and handler.

Both demos read the bundled 10-row synthetic CSV, write eight records and capture two processor skips, then simulate operator approval for row 3 only. Expected final output: `Outcome[written=8, skipped=2, recovered=1, remaining=1, total=9]`. The original eight successful records, original payload and audit history must remain unchanged. The CSV reader is intentionally limited to two unquoted columns; use a supported CSV parser for real files. Email validation is illustrative, not production-grade.

See [quick start](../docs/quickstart.md) and [SQL dialects](../docs/sql-dialects.md) for MySQL, Oracle and H2 configuration. Full standalone job examples are memory and PostgreSQL; the other engines currently have real JDBC contract tests, not dedicated end-to-end consumer jobs.
