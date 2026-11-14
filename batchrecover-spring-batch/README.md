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

The adapter captures only actual processor skips; filtered records, successfully retried errors and other phases are not captured. Use the same DataSource and JDBC transaction manager as the chunk and apply the storage migration first. The repository uses MANDATORY propagation for adapter capture; unsupported or absent transactions fail closed. Parallel processing, read/write skips, different transaction managers and resourceless jobs are unsupported. The input DTO must be immutable (or carry an immutable safe source snapshot); use a stable source occurrence ID and deterministic codec. No automatic listener registration, no replay engine.

See [the sample](../batchrecover-examples) and [integration documentation](../docs/spring-batch-adapter.md).
