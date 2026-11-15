# Approved roadmap — BatchRecover

Mission: Capture. Correct. Replay. A lightweight Java library for Spring Batch; no mandatory database, ORM, web server or UI. Keep contracts in core; choose storage explicitly and expose its real guarantees. No speculative file/NoSQL implementations. Never advertise database support without real backend tests.

| Sprint | Scope | Status |
| --- | --- | --- |
| 0–4 | Foundation, domain, JDBC, Spring Batch capture, recovery engine | Merged |
| 5 | Starter, JaCoCo, OSV, SBOM, local unsigned staging | Merged; 84 Java tests, 94% lines, 70.6% branches |
| 6 | Generic storage capabilities, bounded memory, optional JDBC, shared tests | Merged; 118 Java tests, 95.1% lines, 71.6% branches |
| 7 | Extensible JDBC dialects and schema generation | Merged; 167 tests, all four engines validated |
| 8 | Quality, mutation tests, performance and dependency footprint | Merged; 244 tests, 98.34% lines, 91.10% branches, 93.51% PIT |
| 9 | Developer experience and examples | Merged; 246 tests, 98.43% lines, 91.25% branches, 93.51% PIT |
| 10 | Release 0.1.0 after owner approval | Local signed rehearsal under review; Apache-2.0 approved and namespace verified; official signature and publication pending |

## Sprint 6 — Generic storage
- Core ports and capabilities, bounded memory, explicitly selected JDBC or memory or custom.
- Preserve same-database transaction guarantees; no memory fallback on JDBC errors.
- Contract tests reused by memory and PostgreSQL, capacity and no-database Spring Batch integration.
- Local preview and coverage include memory. No REST or Angular roadmap. No new heavy runtime dependencies.

## Sprint 7 — JDBC dialects
- Shared SQL schema model, versioned migration generation, metadata-based dialect resolution, explicit custom dialect extension.
- PostgreSQL 16, MySQL 8.4, Oracle Free 23 and native H2 2.4 are validated by real-engine CI. MariaDB and SQL Server targeted for 0.2.0; other dialects by demand.
- Host supplies DataSource and drivers, no bundled vendor drivers. Verify real transactional semantics (locks, UUID/time types, constraints, concurrency, rollback and restart). Unsupported engines fail clearly; no generic unsafe SQL fallback.

## Sprint 8 — Quality
See [quality guide](quality.md) for the actual report commands, regression baseline and release targets.
- Target >=95% line/instruction coverage, >=90% branch coverage, >=85% mutation score on critical domain, subject to meaningful assertions, no artificial padding.
- Retain all JDBC rollback/restart/concurrency tests; expand capacity, validation, expiry, storage errors and backend contracts. Use actual Testcontainers and no emulation as proof of dialect support.
- English camelCase test names; Mockito @Mock, @InjectMocks and @Captor when appropriate; @BeforeEach for fixtures. Measure JAR size, transitive dependencies, memory and throughput; assess PIT, JMH and static analysis without runtime baggage.

## Sprint 9 — Developer experience
Document public API/Javadoc, runnable independent CSV consumers for memory and PostgreSQL, quick start, migration and custom extension guide. Explicit listener registration and host-owned worker scheduling remain. Validate independent application integrations.

## Sprint 10 — Release
CI green, quality gates met, review applicable vulnerabilities and licenses, validate namespace and signed local Central bundle. The owner has approved Apache-2.0; publication approval remains separate. Keep 0.1.0-SNAPSHOT until release approval; no Maven Central upload.

## Later versions
0.2.0: additional tested SQL dialects and read/write skip support subject to Spring Batch transaction testing. 0.3.0+: optional file/NoSQL or compatibility extensions only by demonstrated demand. External effects require separate outbox/idempotency design. 1.0.0: stabilized contracts and production feedback. Web/admin UI is not in the library roadmap.
