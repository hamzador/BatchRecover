# Roadmap and quality gates

Status: M0–M3 verified and merged; M4 starter and release preparation under review. CI is the acceptance gate; owner decisions and publication remain pending.

## M0 — Project foundation
- [ ] Confirm proposed Apache-2.0 license and Maven namespace (io.github.hamzador).
- [x] Create Maven reactor, Java 21 toolchain, Maven Wrapper, pinned Boot 4.0.x BOM and compatible Batch 6.0.x.
- [x] Create core, JDBC storage, Spring Batch adapter, Boot starter and example modules.
- [x] Configure GitHub Actions to run ./mvnw -B verify with PostgreSQL Testcontainers.
- [x] Add contribution guide, security reporting policy, dependency updates and reproducible build settings.

Acceptance: fresh clone builds and tests; dependency tree uses the intended Spring versions. No publishing credentials in repository.

## M1 — Domain and storage
See [implementation notes](core-persistence.md). Mark acceptance complete only after CI passes.
- [x] Define rejection identity, payload revisions, status transitions, retry policy and repository ports.
- [x] Versioned PostgreSQL DDL, unique key, revision history, auditing, optimistic updates.
- [x] Integration-test atomic insertion and duplicate capture under concurrent access.

Acceptance: duplicate capture returns original record without mutation; state violations fail; migration works on empty database.

## M2 — Spring Batch capture
See [adapter implementation](spring-batch-adapter.md). CI passed (49 tests).
- [x] Explicit registration for processor skips on opted-in steps.
- [x] Resolve provenance, enforce stable identity and approved payload codec.
- [x] Use the original chunk transaction. Fail closed on repository error.
- [x] Sample job with valid and invalid customers.

Acceptance: sample 100 records, 10 processor skips, 90 successful records, 10 committed rejection rows. A transient failure that succeeds on retry does not produce a rejection; filtered items produce none. Rollback leaves no phantom rejection, restart does not duplicate committed rows. The actual pinned Batch version must pass these tests.

## M3 — Recovery
See [recovery engine](recovery-engine.md). CI passed (73 tests).
- [x] Typed handler registry, correction/revalidation and optimistic locking.
- [x] Explicit selected-item queueing; claim, bounded retry and lease reaper.
- [x] Atomic transactional business effect + success status for same-DB handler.
- [x] Failure classification and immutable attempt/audit history.

Acceptance: correcting and replaying 3 of the 10 rejected sample records produces 3 new valid effects, leaving 7 pending, and does not reprocess the 90 original successes. Two workers never apply the same local effect twice. Crash after claim, rollback after business write, expired lease and stale-token finalization are covered.

## M4 — Developer experience and release
See [starter guide](starter.md) and [release checklist](release-checklist.md). No publication in this sprint.
- [ ] Conditional starter beans; no automatic capture or schema migration by default.
- [ ] Example, configuration reference, compatibility matrix and documented limitations.
- [ ] CI, security review, release notes and artifact publication dry run.
- [ ] Release 0.1.0 only after M0–M4 pass.

## Later milestones
- 0.2: version-specific read/write skip recovery design and tests; optional Spring Batch 5.2 adapter based on demand.
- 0.3: opt-in secured REST API with paging, corrections, audit and recovery requests; Angular dashboard.
- 0.4+: scheduled policies, observability extensions, additional stores or distributed adapters only as justified by user feedback. External effects require separately designed outbox/idempotency strategy.

## Test strategy

JUnit Jupiter 6 (Boot-managed) for domain and services; Mockito only for unit collaborators; Testcontainers PostgreSQL for SQL, transactions and concurrency; Spring Batch integration tests for actual lifecycle semantics. Use English lower camelCase test names and arrange/act/assert. Prefer @ExtendWith(MockitoExtension.class), @Mock, @InjectMocks and @Captor when appropriate. Use @BeforeEach for necessary fixture data, not manual construction of an object already injected by Mockito.

Example planned unit test (illustrative, not compilable until domain types exist):

```java
@ExtendWith(MockitoExtension.class)
class RejectionServiceTest {

    @Mock
    private RejectionRepository repository;

    @InjectMocks
    private RejectionService service;

    @Captor
    private ArgumentCaptor<Rejection> rejectionCaptor;

    @Test
    void shouldPersistRejectedItemWithPendingStatus() {
        // Arrange, act and assert with the implemented API.
    }
}
```

### Required integration scenarios

| Scenario | Expected invariant |
| --- | --- |
| committed processor skip | exactly one rejection for stable source occurrence |
| retry then succeeds | no rejection |
| chunk rollback after capture | no committed phantom rejection |
| restart or repeated callback | no duplicate capture |
| same business key, distinct source offsets | separate rejections |
| concurrent capture | database uniqueness enforced |
| storage failure | step fails instead of losing rejection |
| correction with stale version | rejected without losing history |
| two workers claiming | exclusive valid claim |
| crash after claim | expiry handled; attempt budget respected |
| handler writes then throws | business write and success state roll back |
| stale lease token | cannot finalize new claim |
| retry exhaustion | DEAD, no unbounded retry |
| disabled feature | no capture or job side effects |
| payload containing secret | rejected or protected per configured codec policy |

## Validation before expanding product

Interview Spring Batch maintainers/users about current reject-table solutions; compare job dashboards, retry/restart and queue/dead-letter tooling. Validate the install and replay flow with at least three independent applications. Track adoption, integration friction, recovered records and operational time saved. No claim that BatchRecover has no competitors or that commercial demand is proven.

## Git workflow

Use feature branches, small descriptive commits, pull requests and CI checks after the initial documentation bootstrap. Review public API and schema changes explicitly. Keep examples and documentation synchronized with released behavior.
