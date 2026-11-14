# Customer import example

Standalone Java application with Spring Batch 6 and PostgreSQL. It generates 100 synthetic customers, 10 with invalid email addresses. The listener captures exactly the 10 processor skips and stores the original approved payload and audit; the writer inserts the 90 valid customers. 

Use an **empty disposable PostgreSQL database** only; the sample does not support repeat runs of the same synthetic primary keys without resetting the demo database. Set DEMO_JDBC_URL, DEMO_DB_USER and DEMO_DB_PASSWORD via your shell environment (do not commit real credentials). Build with `./mvnw -pl batchrecover-examples -am package`. From repository root:

```sh
java -jar batchrecover-examples/target/batchrecover-examples-0.1.0-SNAPSHOT.jar --init
```

The explicit --init flag applies the Spring Batch metadata schema, BatchRecover V1 schema and demo table. Omit it only if those schemas already exist and the example table is empty. Expected: status=COMPLETED read=100 written=90 processSkipped=10.

```sql
SELECT count(*) FROM demo_customer; -- 90
SELECT status, count(*) FROM br_rejection GROUP BY status; -- PENDING, 10
SELECT count(*) FROM br_payload_revision; -- 10
SELECT count(*) FROM br_audit_event; -- 10
```

The integration tests use temporary PostgreSQL Testcontainers and verify actual job execution, rollback, restart, retry/filter behavior, storage errors and opt-in. No external database is touched by tests.
