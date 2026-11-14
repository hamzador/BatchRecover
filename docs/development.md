# Development setup

- Java 21 minimum (compiled with release 21)
- Maven 3.9.12 via Apache Maven Wrapper 3.3.4 (only-script, Windows and POSIX)
- Spring Boot 4.0.9 parent manages Spring Batch 6.0.6, JUnit Jupiter 6.0.3, Mockito 5.20.0 and Testcontainers 2.0.5. Use the BOM instead of overriding the aligned JUnit platform with older versions.
- PostgreSQL 16 containers for JDBC transaction, concurrency and contract tests. Memory tests and the resourceless batch do not need a database.

```sh
./mvnw -B test
./mvnw -B clean verify
```

On Windows use `mvnw.cmd`. Docker is required for verify; test runs only unit tests. CI runs the release-preparation clean install on Linux and uploads XML reports. No deployment or release workflow is enabled; Maven Central credentials and licensing are not decided.

Core capture, correction, replay, JDBC rollback/restart and concurrency tests are implemented. Sprint 6 adds bounded memory, shared contract tests and database-free Spring Batch integration. See [storage](storage.md) and the approved [roadmap](roadmap.md).

References: [Boot 4.0 managed coordinates](https://docs.spring.io/spring-boot/4.0/appendix/dependency-versions/coordinates.html), [Maven Wrapper](https://maven.apache.org/tools/wrapper/).

## Quality and local release preview

`./mvnw -B -Prelease-preparation clean install` runs tests, attaches source/Javadoc archives and generates coverage reports. The internal `batchrecover-coverage` module aggregates library coverage, including execution data from the example. CI also creates a CycloneDX SBOM, audits known dependency advisories and stages a local unsigned preview. See [release checklist](release-checklist.md). The Maven Central publication step is deliberately absent.
