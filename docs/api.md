# Java API and extension guide

Public entry points, all pre-1.0 and subject to documented changes before release:

| API | Purpose |
| --- | --- |
| RejectionService | Capture, get, correct, queue, discard with optimistic version |
| RecoveryStorage | Capabilities, bounded pending query, revisions, audit, attempts |
| PayloadCodec / VersionedPayloadCodec | Typed logical payload ID and readable schema versions |
| RecordIdentity | Stable source occurrence identity |
| RecoveryHandler / Registration | Typed business validation and synchronous replay |
| BatchRecoverListenerFactory | Build explicitly enrolled processor skip listener |
| BatchRecoverWorker / RecoveryEngine | Host-owned polling and lease maintenance |
| SqlDialect / SqlDialectRegistry | Optional JDBC extension and explicit metadata matching |
| SchemaGenerator / SchemaCli | Offline migration scripts |

Core is pure Java and has no Spring dependency. To integrate without Boot, instantiate a provider, RejectionService with a Clock, RecoveryHandlerRegistry with typed registrations, RecoveryEngine with RecoverySettings, and RecoverableSkipListener with CaptureSettings. The existing [direct JDBC example](../batchrecover-examples/README.md) shows manual setup. Boot automates this wiring but never starts the job or worker.

## Extend storage

Implement RecoveryStorage (or both RejectionRepository and RecoveryRepository) and advertise only real capabilities. Implement atomic metadata transitions, optimistic versions, immutable original payload, duplicate identity checks and fenced claims. Transactional providers must join the physical capture transaction and commit the replay effect with RECOVERED state or roll both back. Install both ports with `storage.type: custom` and test the common contract from the core test sources. In starter custom mode capture is mandatory transactional by default; a nontransactional custom provider requires explicitly configured manual listener wiring, not silent fallback. Do not use arbitrary SQL or user-controlled class names. See [storage](storage.md) for limits.

## Extend JDBC

A custom SqlDialect bean or explicit SqlDialectRegistry extension provides metadata matching, UUID/time mappings, database clock, limits/locking, deduplication strategy and versioned migration SQL; inspect the interface and [dialect guide](sql-dialects.md). Built-in names are reserved. A dialect is not supported merely because it can generate DDL: execute the full contract, rollback, concurrent queue and lease tests against a real engine. No custom driver bundled.

## Upgrade and data lifecycle

Keep all BatchRecover artifacts at the same version; never modify already-applied V1/V2 migrations. Existing PostgreSQL V1/V2 SQL remains unchanged. When upgrading from an earlier starter snapshot, add JDBC explicitly because it is no longer transitive. No cross-engine migration, auto purge or retention job. Plan backup and retention in the host and ensure sensitive payload access is authorized. Keep older codec decoders until all retained revisions have been handled; never reinterpret old bytes with a different schema.

## Javadoc

Generate and attach source and Javadoc archives with `./mvnw -B -ntp -Prelease-preparation clean install`. Each of the five library modules has its own Javadoc JAR in target; open index.html from that archive. Documentation includes API contracts and package-level guidance; internal implementation details are not a supported extension API.
