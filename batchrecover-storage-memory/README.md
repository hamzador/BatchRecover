# Memory storage

Java-only bounded storage for one-shot batch jobs, tests and local nontransactional recovery. No driver, server, or Spring dependency. Use `new InMemoryRecoveryStorage(Clock.systemUTC(), MemoryStorageLimits.DEFAULT)` with the core RejectionService/RecoveryEngine, or explicitly select memory in the starter.

All state is lost at JVM exit. No silent eviction, no transaction participation and no rollback of handler effects. Capacity includes terminal records and original/corrected payloads. Configure limits, keep handlers idempotent and approve replay only after the original job has finished. See [storage contract and configuration](../docs/storage.md).
