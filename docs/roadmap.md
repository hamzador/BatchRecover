# Approved roadmap — BatchRecover

Mission: Capture. Correct. Replay. A lightweight Java library for Spring Batch; no mandatory database, ORM, web server or UI. Keep contracts in core; choose storage explicitly and expose its real guarantees. No speculative file/NoSQL implementations. Never advertise database support without real backend tests.

| Sprint | Scope | Status |
| --- | --- | --- |
| 0–4 | Foundation, domain, JDBC, Spring Batch capture, recovery engine | Merged |
| 5 | Starter, JaCoCo, OSV, SBOM, local unsigned staging | Merged; 84 Java tests, 94% lines, 70.6% branches |
| 6 | Generic storage capabilities, bounded memory, optional JDBC, shared tests | Implementation under review |
| 7 | Extensible JDBC dialects and schema generation | Planned |
| 8 | Quality, mutation tests, performance and dependency footprint | Planned |
| 9 | Developer experience and examples | Planned |
| 10 | Release 0.1.0 after owner approval | Planned |

## Sprint 6 — Generic storage
- Core ports and capabilities, bounded memory, explicitly selected JDBC or memory or custom.
- Preserve same-database transaction guarantees; no memory fallback on JDBC errors.
- Contract tests reused by memory and PostgreSQL, capacity and no-database Spring Batch integration.
- Local preview and coverage include memory. No REST or Angular roadmap. No new heavy runtime dependencies.

## Sprint 7 — JDBC dialects
- Shared SQL schema model, versioned migration generation, metadata-based dialect resolution, explicit custom dialect extension.
- PostgreSQL remains the only currently validated SQL backend. Target PostgreSQL, MySQL, Oracle and H2 for 0.1.0, only if verified individually. MariaDB and SQL Server targeted for 0.2.0; other dialects by demand.
- Host supplies DataSource and drivers, no bundled vendor drivers. Verify real transactional semantics (locks, UUID/time types, constraints, concurrency, rollback and restart). Unsupported engines fail clearly; no generic unsafe SQL fallback.

## Sprint 8 — Quality
- Target >=95% line/instruction coverage, >=90% branch coverage, >=85% mutation score on critical domain, subject to meaningful assertions, no artificial padding.
- Retain all JDBC rollback/restart/concurrency tests; expand capacity, validation, expiry, storage errors and backend contracts. Use actual Testcontainers and no emulation as proof of dialect support.
- English camelCase test names; Mockito @Mock, @InjectMocks and @Captor when appropriate; @BeforeEach for fixtures. Measure JAR size, transitive dependencies, memory and throughput; assess PIT, JMH and static analysis without runtime baggage.

## Sprint 9 — Developer experience
Document public API/Javadoc, quick start without a database, supported JDBC examples, migration and custom extension guide. Explicit listener registration and host-owned worker scheduling remain. Validate independent application integrations.

## Sprint 10 — Release
CI green, quality gates met, review applicable vulnerabilities and licenses, validate namespace and signed local Central bundle. Owner must approve license and publication separately. Keep 0.1.0-SNAPSHOT until release approval; no Maven Central upload.

## Later versions
0.2.0: additional tested SQL dialects and read/write skip support subject to Spring Batch transaction testing. 0.3.0+: optional file/NoSQL or compatibility extensions only by demonstrated demand. External effects require separate outbox/idempotency design. 1.0.0: stabilized contracts and production feedback. Web/admin UI is not in the library roadmap.
