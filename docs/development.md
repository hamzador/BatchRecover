# Development setup

- Java 21 minimum (compiled with release 21)
- Maven 3.9.12 via Apache Maven Wrapper 3.3.4 (only-script, Windows and POSIX)
- Spring Boot 4.0.9 parent manages Spring Batch 6.0.6, JUnit Jupiter 6.0.3, Mockito 5.20.0 and Testcontainers 2.0.5. Use the BOM instead of overriding the aligned JUnit platform with older versions.
- PostgreSQL 16, MySQL 8.4 and Oracle Free 23 containers plus native H2 2.4 for JDBC transaction, concurrency and contract tests. Memory tests and the resourceless batch do not need a database.

```sh
./mvnw -B test
./mvnw -B clean verify
```

On Windows use `mvnw.cmd`. Docker is required for verify; test runs only unit tests. CI runs the release-preparation clean install on Linux and uploads XML reports. No deployment or release workflow is enabled; Apache-2.0 is adopted; no publishing credentials are needed. CI uses a fresh hosted runner without Maven dependency caching, so no unofficial 0.1.0 is cached between jobs.

Core capture, correction, replay, JDBC rollback/restart and concurrency tests are implemented. Bounded memory, common contracts, dialect generation and database-free Spring Batch integration are implemented; all four JDBC backends are tested. See [storage](storage.md) and the approved [roadmap](roadmap.md).

References: [Boot 4.0 managed coordinates](https://docs.spring.io/spring-boot/4.0/appendix/dependency-versions/coordinates.html), [Maven Wrapper](https://maven.apache.org/tools/wrapper/).

## Quality and local release preview (0.1.0)

`./mvnw -B -Prelease-preparation clean install` runs tests, attaches source/Javadoc archives and generates coverage reports. The internal `batchrecover-coverage` module aggregates library coverage, including execution data from the example. CI also creates a CycloneDX SBOM, audits known dependency advisories and stages a local unsigned preview and signs the tested 0.1.0 artifacts with a disposable key. See [release checklist](release-checklist.md). The Maven Central publication step is deliberately absent.

## Independent consumer verification

After the root install, run the two standalone builds from the repository root:

```sh
./mvnw -B -ntp -f consumer-examples/memory/pom.xml clean verify
./mvnw -B -ntp -f consumer-examples/jdbc/pom.xml clean verify
java -jar consumer-examples/memory/target/batchrecover-memory-consumer-1.0.0-SNAPSHOT.jar
```

Both examples have their own Spring Boot parent and depend on installed library JARs, not the reactor source. See [consumer README](../consumer-examples/README.md). The quality pipeline also runs strict JaCoCo/PIT/SpotBugs thresholds and JMH smoke benchmarks; see [quality](quality.md).
