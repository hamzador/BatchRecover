# Contributing

BatchRecover 0.1.0 is prepared but not yet published; contributions are subject to the repository's Apache-2.0 license. Please open an issue to discuss proposed changes before submitting a patch.

## Build

Install JDK 21 and Docker. Maven Wrapper downloads Maven 3.9.12. Run:

```sh
./mvnw -B clean verify
```

Run unit tests without Docker using `./mvnw test`. The JDBC integration test runs during `verify` and requires a working Docker daemon; do not skip it when reviewing changes.

## Coding conventions

English lower camelCase method and test names. Prefer constructor injection. Use JUnit Jupiter and Mockito's @ExtendWith(MockitoExtension.class), @Mock, @InjectMocks and @Captor where appropriate; don't mock value objects. Add regression tests for behavior changes.

Use feature branches and pull requests. Changes to transaction or recovery semantics require integration tests. Do not add credentials or real payloads to the repository.
