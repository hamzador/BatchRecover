package io.github.hamzador.batchrecover.examples;

import io.github.hamzador.batchrecover.core.domain.ErrorCategory;
import io.github.hamzador.batchrecover.core.service.RejectionService;
import io.github.hamzador.batchrecover.core.service.RecoveryEngine;
import io.github.hamzador.batchrecover.core.service.RecoverySettings;
import io.github.hamzador.batchrecover.core.service.RecoveryHandlerRegistry;
import io.github.hamzador.batchrecover.core.service.RetryPolicy;
import io.github.hamzador.batchrecover.core.domain.RecoveryResult;
import java.time.Duration;
import io.github.hamzador.batchrecover.springbatch.*;
import io.github.hamzador.batchrecover.storage.jdbc.JdbcRejectionRepository;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.configuration.support.MapJobRegistry;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.support.TaskExecutorJobOperator;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.repository.support.JdbcJobRepositoryFactoryBean;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.infrastructure.item.ItemProcessor;
import org.springframework.batch.infrastructure.item.ItemWriter;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.jdbc.support.JdbcTransactionManager;

import javax.sql.DataSource;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

/** Standalone example; explicit initialization is permitted only on a fresh demo database. */
public final class CustomerImportExample {
    private final JdbcTemplate jdbc;
    private final JdbcTransactionManager transactions;
    private final JobRepository jobRepository;
    private final TaskExecutorJobOperator operator;
    private final RejectionService service;
    private final JdbcRejectionRepository rejections;

    public CustomerImportExample(DataSource dataSource) throws Exception {
        jdbc = new JdbcTemplate(dataSource);
        transactions = new JdbcTransactionManager(dataSource);
        var factory = new JdbcJobRepositoryFactoryBean();
        factory.setDataSource(dataSource);
        factory.setTransactionManager(transactions);
        factory.afterPropertiesSet();
        jobRepository = factory.getObject();
        operator = new TaskExecutorJobOperator();
        operator.setJobRepository(jobRepository);
        operator.setJobRegistry(new MapJobRegistry());
        operator.afterPropertiesSet();
        rejections = new JdbcRejectionRepository(dataSource, transactions);
        service = new RejectionService(rejections, Clock.systemUTC());
    }

    public static void initialize(DataSource dataSource) {
        new ResourceDatabasePopulator(
                new ClassPathResource("org/springframework/batch/core/schema-postgresql.sql"),
                new ClassPathResource("db/batchrecover/V1__create_batchrecover_tables.sql"),
                new ClassPathResource("db/batchrecover/V2__scope_worker_indexes.sql"),
                new ClassPathResource("db/example/customer.sql")).execute(dataSource);
    }

    public static List<Customer> sampleRows() {
        return IntStream.rangeClosed(1, 100)
                .mapToObj(i -> new Customer(i, i % 10 == 0 ? "invalid" : "customer" + i + "@example.org"))
                .toList();
    }

    public static Customer validate(Customer customer) {
        if (!customer.email().contains("@")) {
            throw new InvalidCustomerException();
        }
        return customer;
    }

    public ItemWriter<Customer> writer() {
        return chunk -> {
            for (Customer customer : chunk) {
                jdbc.update("INSERT INTO demo_customer (source_row, email) VALUES (?, ?)",
                        customer.sourceRow(), customer.email());
            }
        };
    }

    public Job job(List<Customer> rows, ItemProcessor<Customer, Customer> processor,
                   ItemWriter<Customer> writer, Class<? extends Throwable> retryable,
                   boolean captureEnabled, int chunkSize) {
        var builder = new StepBuilder("customerImportStep", jobRepository)
                .<Customer, Customer>chunk(chunkSize)
                .transactionManager(transactions)
                .reader(new CustomerReader(rows))
                .processor(processor)
                .writer(writer)
                .faultTolerant()
                .skip(InvalidCustomerException.class)
                .skipLimit(10);
        if (retryable != null) {
            builder.retry(retryable).retryLimit(2);
        }
        if (captureEnabled) {
            builder.skipListener(new RecoverableSkipListener<>(
                    service, new BatchCaptureContextResolver(),
                    customer -> "synthetic-v1:" + customer.sourceRow(), new CustomerPayloadCodec(),
                    failure -> failure instanceof InvalidCustomerException
                            ? new ClassifiedError("INVALID_EMAIL", ErrorCategory.BUSINESS)
                            : ErrorClassifier.unknown().classify(failure),
                    new CaptureSettings("customer-demo", "customer-import", 3)));
        }
        return new JobBuilder("customerImport", jobRepository).start(builder.build()).build();
    }

    public JobExecution run(Job job, String inputId) throws Exception {
        return operator.start(job, new JobParametersBuilder().addString("inputId", inputId).toJobParameters());
    }

    public int recoverThreeCustomers() {
        var ids = jdbc.query("""
                SELECT id FROM br_rejection WHERE application_scope='customer-demo' AND status='PENDING'
                ORDER BY source_occurrence_id LIMIT 3
                """, (rs, row) -> rs.getObject("id", UUID.class));
        var codec = new CustomerPayloadCodec();
        for (UUID id : ids) {
            var current = service.get(id);
            var original = rejections.findRevisions(id).getFirst().payload();
            var customer = codec.decode(original.encoded(), original.schemaVersion());
            var corrected = new Customer(customer.sourceRow(), "recovered" + customer.sourceRow() + "@example.org");
            var updated = service.correct(id, current.version(),
                    new io.github.hamzador.batchrecover.core.domain.ReplayPayload(codec.typeId(), codec.schemaVersion(), codec.encode(corrected)),
                    "demo-operator", "correct synthetic email");
            service.queue(id, updated.version(), "demo-operator", "request replay");
        }
        var registry = new RecoveryHandlerRegistry(List.of(
                new RecoveryHandlerRegistry.Registration<>("customer-import", codec,
                        new CustomerRecoveryHandler(jdbc))));
        var engine = new RecoveryEngine(rejections, registry,
                new RecoverySettings("customer-demo", Duration.ofSeconds(30),
                        new RetryPolicy(Duration.ofSeconds(1), Duration.ofMinutes(1))));
        int recovered = 0;
        for (int i = 0; i < ids.size(); i++) {
            if (engine.runOnce() == RecoveryResult.RECOVERED) recovered++;
        }
        return recovered;
    }

    public static void main(String[] args) throws Exception {
        var source = new DriverManagerDataSource(requiredEnv("DEMO_JDBC_URL"),
                requiredEnv("DEMO_DB_USER"), requiredEnv("DEMO_DB_PASSWORD"));
        if (List.of(args).contains("--init")) {
            initialize(source);
        }
        var demo = new CustomerImportExample(source);
        var job = demo.job(sampleRows(), CustomerImportExample::validate,
                demo.writer(), null, true, 10);
        var execution = demo.run(job, UUID.randomUUID().toString());
        var step = execution.getStepExecutions().iterator().next();
        System.out.printf("status=%s read=%d written=%d processSkipped=%d%n",
                execution.getStatus(), step.getReadCount(), step.getWriteCount(), step.getProcessSkipCount());
        if (execution.getStatus() != BatchStatus.COMPLETED || step.getWriteCount() != 90
                || step.getProcessSkipCount() != 10) {
            throw new IllegalStateException("Unexpected sample outcome");
        }
        if (List.of(args).contains("--recover-three")) {
            int recovered = demo.recoverThreeCustomers();
            System.out.printf("recovered=%d customers=%d%n", recovered,
                    demo.jdbc.queryForObject("SELECT count(*) FROM demo_customer", Integer.class));
            if (recovered != 3) throw new IllegalStateException("Unexpected recovery outcome");
        }
    }

    private static String requiredEnv(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Set " + key);
        }
        return value;
    }
}
