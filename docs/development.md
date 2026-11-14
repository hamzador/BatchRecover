# Development setup

- Java 21 minimum (compiled with release 21)
- Maven 3.9.12 via Apache Maven Wrapper 3.3.4 (only-script, Windows and POSIX)
- Spring Boot 4.0.9 parent manages Spring Batch 6.0.6, JUnit Jupiter 6.0.3, Mockito 5.20.0 and Testcontainers 2.0.5. Use the BOM instead of overriding the aligned JUnit platform with older versions.
- PostgreSQL 16 container for the connectivity test.

```sh
./mvnw -B test
./mvnw -B clean verify
```

On Windows use `mvnw.cmd`. Docker is required for verify; test runs only unit tests. CI runs clean verify on Linux and uploads XML reports. No deployment or release workflow is enabled; Maven Central credentials and licensing are not decided.

Sprint 1 adds initial domain types, dependency wiring, an opt-in configuration property (no functional recovery), smoke tests and CI. Phase is implicit in the identity because only processor skips are planned for the first release; add phase before multi-phase capture is introduced. The integration test only checks PostgreSQL connectivity, not rejection persistence or replay. Actual transaction guarantees must be proven in later milestones.

References: [Boot 4.0 managed coordinates](https://docs.spring.io/spring-boot/4.0/appendix/dependency-versions/coordinates.html), [Maven Wrapper](https://maven.apache.org/tools/wrapper/).
