# Spring Batch 6 adapter

Register the listener explicitly on a sequential, fault-tolerant chunk step using the Batch 6 builder:

```java
var listener = new RecoverableSkipListener<Customer, Customer>(
    rejectionService, new BatchCaptureContextResolver(),
    customer -> "immutable-source:" + customer.sourceRow(), customerCodec,
    failure -> new ClassifiedError("INVALID_EMAIL", ErrorCategory.BUSINESS),
    new CaptureSettings("customer-app", "customer-import", 3));

return new StepBuilder("customerImport", jobRepository)
    .<Customer, Customer>chunk(100)
    .transactionManager(transactionManager)
    .reader(reader).processor(processor).writer(writer)
    .faultTolerant().skip(InvalidCustomerException.class).skipLimit(100)
    .skipListener(listener).build();
```

The adapter captures only actual processor skips; filtered records, successfully retried errors and other phases are not captured. In JDBC mode use the same DataSource and JDBC transaction manager as the chunk and apply the storage migration first. The repository uses MANDATORY propagation for adapter capture; unsupported or absent transactions fail closed. Parallel processing and read/write skips are unsupported; JDBC mode requires compatible transactions. Explicit memory mode supports one-shot resourceless jobs without rollback guarantees. The input DTO must be immutable (or carry an immutable safe source snapshot); use a stable source occurrence ID and deterministic codec. No automatic listener registration, replay is handled separately by the core recovery engine.

See [the sample](../batchrecover-examples) and [integration documentation](../docs/spring-batch-adapter.md).
