# Spring Boot starter (0.1.0 target)

Tested target: Java 21, Spring Boot 4.0.9, Spring Batch 6.0.6; bounded memory and PostgreSQL 16, MySQL 8.4, Oracle Free 23, native H2 2.4 are tested. Other versions and distributed processing are not certified. Install the SNAPSHOT locally with `./mvnw -B clean install` (Docker required for integration tests); there is no published BatchRecover artifact yet.

```xml
<dependency>
    <groupId>io.github.hamzador</groupId>
    <artifactId>batchrecover-spring-boot-starter</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

For JDBC mode, explicitly add the batchrecover-storage-jdbc artifact at the same version (it is no longer transitive through the starter). The host supplies its Spring Batch JDBC job repository, appropriate database driver, DataSource and the same JdbcTransactionManager used by the chunk. Boot may supply that manager if JDBC auto-configuration is present; verify it is the intended manager. A generic JPA manager is not supported by this first version. With multiple managers, explicitly select the primary compatible one or provide both repository ports; the starter fails startup rather than silently choosing an ambiguous manager. Apply V1 and V2 with the host migration tool before enabling the starter. No automatic migration is performed. Keep batchrecover tables protected by host authorization and data retention.

```yaml
batchrecover:
  enabled: true
  application-scope: customer-app
  storage:
    type: jdbc
    dialect: auto
  max-attempts: 3
  lease-duration: 30s
  initial-retry-delay: 1s
  maximum-retry-delay: 1m
  metrics-enabled: true
```

| Property | Default | Meaning |
| --- | --- | --- |
| enabled | false | Opt in to auto-configured local APIs |
| application-scope | unset | Required on enablement, stable queue isolation |
| max-attempts | 3 | 1–100, captured per new rejection |
| lease-duration | 30s | 1 ms–24 h |
| initial-retry-delay | 1s | At least 1 ms |
| maximum-retry-delay | 1m | At least initial delay, at most 30 days |
| metrics-enabled | true | Record worker metrics if a unique or primary MeterRegistry exists |
| storage.type | jdbc | jdbc, memory, or custom; explicitly select memory without a database |
| storage.dialect | auto | JDBC metadata detection or a registered dialect name |
| storage.max-records | 1000 | Memory-only total retained records, including terminal records |
| storage.max-revisions-per-record | 8 | Memory-only retained revisions per record |
| storage.max-payload-bytes | 16777216 | Memory-only cumulative encoded payload bytes; not total heap size |

Use typed duration syntax with a unit. No jobs, worker threads, scheduling or listener registration are automatically started. No database connection is opened by BatchRecover during bean creation. In JDBC mode the application must provide the same transaction manager for both capture and worker JDBC writes. Custom repository implementations must implement both ports and uphold the transaction/fencing contract. A custom RejectionService, RecoveryEngine, registry, listener factory or worker bean can replace the default.

## Storage selection

The default when enabled remains JDBC for compatibility; use `storage.type: memory` explicitly for a one-shot JVM without a database. Memory does not share chunk transaction rollback, loses history at JVM exit and fails at its configured capacity limits. For custom ports, use `storage.type: custom`. See [storage capabilities, limits and configuration](storage.md). JDBC never falls back to memory. The no-database mode has a real resourceless Spring Batch integration test.

See [runnable standalone examples](../consumer-examples/README.md) for actual Boot auto-configuration discovery, not hand-created starter beans.

## Register a typed handler and listener

```java
@Bean
RecoveryHandlerRegistry.Registration<Customer> customerRecovery(
        CustomerPayloadCodec codec, CustomerRecoveryHandler handler) {
    return new RecoveryHandlerRegistry.Registration<>("customer", codec, handler);
}

@Bean
RecoverableSkipListener<Customer, Customer> customerSkips(
        BatchRecoverListenerFactory factory, CustomerPayloadCodec codec) {
    return factory.create("customer",
            customer -> "source-v1:" + customer.sourceRow(),
            codec, failure -> new ClassifiedError("INVALID_EMAIL", ErrorCategory.BUSINESS));
}
```

Add the listener explicitly to a sequential fault-tolerant chunk builder with `.skipListener(customerSkips)`. The host owns skip/retry policies, stable source identity, schema-versioned safe payloads, error classification, corrected payload approval and business validation. See [adapter](spring-batch-adapter.md), [core API](core-persistence.md) and [worker semantics](recovery-engine.md).

Use RejectionService to capture/correct/queue/discard explicit records; queueing alone does not replay. Inject BatchRecoverWorker and call runOnce from an application-owned bounded worker loop outside any transaction. For maintenance call reapOnce. Each poll processes at most one queued record and reaps at most one expired claim. No external side effects or separately committed business writes.

## Micrometer

With a compatible MeterRegistry (e.g. provided by the host's Actuator integration), the worker facade records two timers with a single bounded outcome tag:

- batchrecover.worker.poll: NO_WORK, RECOVERED, STALE_CLAIM, RETRY_SCHEDULED, CORRECTION_REQUIRED, DEAD or ERROR.
- batchrecover.worker.reap: REAPED, NO_WORK or ERROR (explicit reapOnce calls only).

Timer count measures poll/reap calls; total time includes repository work. Calling the core RecoveryEngine directly bypasses these metrics. An internal reaper invocation during runOnce contributes to poll duration, not a separate reaper metric. No capture counters, backlog gauges or per-record identifiers are exposed yet. No registry means no metrics and does not prevent recovery. Metrics recording errors do not change recovery results. No HTTP endpoints or registry/exporter are created automatically. Configure the host registry and exporter as needed.

## Operational boundaries

Secure stored payloads and audit metadata, manage access to the local API and database, and synchronize host and database clocks. BatchRecover does not supply authentication, arbitrary data masking, automatic payload encryption, or remote exactly-once semantics. No production certification. Review the [release checklist](release-checklist.md) before publishing.

For JDBC dialect detection, explicit configuration, custom extensions and offline generation of migrations, see [SQL dialects](sql-dialects.md). A driver must be supplied by the host.
