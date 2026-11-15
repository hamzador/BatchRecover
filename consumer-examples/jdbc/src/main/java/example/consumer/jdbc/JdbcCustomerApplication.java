package example.consumer.jdbc;

import io.github.hamzador.batchrecover.core.codec.VersionedPayloadCodec;
import io.github.hamzador.batchrecover.core.domain.*;
import io.github.hamzador.batchrecover.core.exception.RecoveryValidationException;
import io.github.hamzador.batchrecover.core.port.*;
import io.github.hamzador.batchrecover.core.service.*;
import io.github.hamzador.batchrecover.springbatch.ClassifiedError;
import io.github.hamzador.batchrecover.starter.*;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.configuration.support.MapJobRegistry;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.support.TaskExecutorJobOperator;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.infrastructure.item.support.ListItemReader;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.batch.core.repository.support.JdbcJobRepositoryFactoryBean;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.jdbc.support.JdbcTransactionManager;
import javax.sql.DataSource;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

/** A complete one-shot sample using only installed BatchRecover artifacts and host dependencies. */
@SpringBootConfiguration
@EnableAutoConfiguration
public class JdbcCustomerApplication {
    private static final String SCOPE = "customer-demo";
    private static final String HANDLER = "customer-import";
    record Customer(int row, String email) {}
    record Outcome(int written, int skipped, int recovered, int remaining, int total) {}
    static final class InvalidCustomer extends RuntimeException {}

    @Bean
    PayloadCodec<Customer> customerCodec() {
        // Fixed DTO format for synthetic data only; no arbitrary class deserialization.
        return new VersionedPayloadCodec<>("customer", 1, c -> c.row() + ":" + c.email(),
                Map.of(1, value -> {
                    String[] fields = value.split(":", 2);
                    return new Customer(Integer.parseInt(fields[0]), fields[1]);
                }));
    }

    @Bean
    DataSource demoDataSource(Environment env) {
        return new DriverManagerDataSource(env.getRequiredProperty("demo.jdbc-url"),
                env.getRequiredProperty("demo.db-user"), env.getRequiredProperty("demo.db-password"));
    }

    @Bean
    JdbcTransactionManager transactionManager(DataSource source) {
        return new JdbcTransactionManager(source);
    }

    @Bean
    JdbcTemplate demoJdbc(DataSource source) {
        return new JdbcTemplate(source);
    }

    @Bean
    RecoveryHandlerRegistry.Registration<Customer> customerRecovery(PayloadCodec<Customer> codec,
            JdbcTemplate jdbc) {
        return new RecoveryHandlerRegistry.Registration<>(HANDLER, codec, new RecoveryHandler<>() {
            @Override
            public void validate(Customer item, RecoveryContext ctx) {
                if (!ctx.identity().sourceOccurrenceId().equals("customers-v1:" + item.row())
                        || !item.email().contains("@")) throw new RecoveryValidationException();
            }

            @Override
            public void replay(Customer item, RecoveryContext ctx) {
                jdbc.update("INSERT INTO demo_customer(source_row,email) VALUES (?,?) ON CONFLICT(source_row) DO NOTHING",
                        item.row(), item.email());
                String stored = jdbc.queryForObject("SELECT email FROM demo_customer WHERE source_row=?",
                        String.class, item.row());
                if (!item.email().equals(stored)) throw new RecoveryValidationException();
            }
        });
    }

    static ConfigurableApplicationContext start(String... args) {
        return new SpringApplicationBuilder(JdbcCustomerApplication.class)
                .web(WebApplicationType.NONE).run(args);
    }

    @SuppressWarnings("unchecked")
    static Outcome run(ConfigurableApplicationContext context) throws Exception {
        var source = context.getBean(DataSource.class);
        var jdbc = context.getBean(JdbcTemplate.class);
        // Explicit, disposable demo initialization only. Never use a production database.
        if (context.getEnvironment().getProperty("demo.initialize", Boolean.class, false)) {
            new ResourceDatabasePopulator(
                    new ClassPathResource("org/springframework/batch/core/schema-postgresql.sql"),
                    new ClassPathResource("db/batchrecover/V1__create_batchrecover_tables.sql"),
                    new ClassPathResource("db/batchrecover/V2__scope_worker_indexes.sql"),
                    new ClassPathResource("demo-customer.sql")).execute(source);
        }
        var transactions = context.getBean(JdbcTransactionManager.class);
        var factory = new JdbcJobRepositoryFactoryBean();
        factory.setDataSource(source);
        factory.setTransactionManager(transactions);
        factory.afterPropertiesSet();
        var jobRepository = factory.getObject();
        if (jdbc.queryForObject("SELECT count(*) FROM demo_customer", Integer.class) != 0) {
            throw new IllegalStateException("Use an empty disposable demo database");
        }
        var codec = (PayloadCodec<Customer>) context.getBean("customerCodec");
        var listener = context.getBean(BatchRecoverListenerFactory.class)
                .<Customer, Customer>create(HANDLER, item -> "customers-v1:" + item.row(),
                        codec, failure -> new ClassifiedError("INVALID_EMAIL", ErrorCategory.BUSINESS));
        var step = new StepBuilder("customerImport", jobRepository)
                .<Customer, Customer>chunk(5)
                .transactionManager(transactions)
                .reader(new ListItemReader<>(readCustomers()))
                .processor(item -> {
                    if (!item.email().contains("@")) throw new InvalidCustomer();
                    return item;
                })
                .writer(chunk -> {
                    for (Customer item : chunk) {
                        jdbc.update("INSERT INTO demo_customer(source_row,email) VALUES (?,?)", item.row(), item.email());
                    }
                })
                .faultTolerant().skip(InvalidCustomer.class).skipLimit(2)
                .skipListener(listener).build();
        var job = new JobBuilder("customerImport", jobRepository).start(step).build();
        var operator = new TaskExecutorJobOperator();
        operator.setJobRepository(jobRepository);
        operator.setJobRegistry(new MapJobRegistry());
        operator.afterPropertiesSet();
        var execution = operator.start(job, new JobParametersBuilder().addString("inputId", "customers-v1").toJobParameters());
        var stats = execution.getStepExecutions().iterator().next();
        require(execution.getStatus() == BatchStatus.COMPLETED && stats.getWriteCount() == 8
                && stats.getProcessSkipCount() == 2, "Unexpected job result");

        var originals = jdbc.queryForList("SELECT source_row,email FROM demo_customer ORDER BY source_row");
        var storage = context.getBean(RecoveryStorage.class);
        var service = context.getBean(RejectionService.class);
        var pending = storage.findPending(SCOPE, 10);
        require(pending.size() == 2, "Expected two captured rejects");
        // Simulated operator approval for row 3 only; never auto-correct real business data.
        var chosen = pending.stream().filter(r -> r.identity().sourceOccurrenceId().equals("customers-v1:3"))
                .findFirst().orElseThrow();
        var original = storage.findRevisions(chosen.id()).getFirst().payload();
        var customer = codec.decode(original.encoded(), original.schemaVersion());
        var fixed = new Customer(customer.row(), "corrected3@example.org");
        var corrected = service.correct(chosen.id(), chosen.version(),
                new ReplayPayload(codec.typeId(), codec.schemaVersion(), codec.encode(fixed)),
                "demo-operator", "approved synthetic correction");
        service.queue(chosen.id(), corrected.version(), "demo-operator", "replay selected row");
        var worker = context.getBean(BatchRecoverWorker.class);
        require(worker.runOnce() == RecoveryResult.RECOVERED, "Expected recovery");
        require(worker.runOnce() == RecoveryResult.NO_WORK, "Expected no additional queued work");

        require(service.get(chosen.id()).status() == RejectionStatus.RECOVERED, "Expected terminal recovery");
        require(storage.findRevisions(chosen.id()).size() == 2
                && storage.findRevisions(chosen.id()).getFirst().payload().equals(original), "Original history changed");
        require(storage.findAttempts(chosen.id()).size() == 1
                && storage.findAttempts(chosen.id()).getFirst().outcome() == AttemptOutcome.RECOVERED, "Unexpected attempt history");
        require(storage.findAuditEvents(chosen.id()).size() == 5, "Unexpected audit history");
        var after = jdbc.queryForList("SELECT source_row,email FROM demo_customer ORDER BY source_row");
        require(after.containsAll(originals), "Original successful rows changed");
        int total = after.size();
        int remaining = storage.findPending(SCOPE, 10).size();
        require(total == 9 && remaining == 1, "Unexpected final state");
        return new Outcome(8, 2, 1, remaining, total);
    }

    private static List<Customer> readCustomers() throws Exception {
        // The bundled CSV has exactly two unquoted columns; use a real CSV parser for general input.
        try (var reader = new BufferedReader(new InputStreamReader(
                Objects.requireNonNull(JdbcCustomerApplication.class.getResourceAsStream("/customers.csv")), StandardCharsets.UTF_8))) {
            return reader.lines().skip(1).map(line -> {
                var fields = line.split(",", -1);
                if (fields.length != 2) throw new IllegalArgumentException("Unexpected sample CSV format");
                return new Customer(Integer.parseInt(fields[0]), fields[1]);
            }).collect(Collectors.toCollection(ArrayList::new));
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    public static void main(String[] args) throws Exception {
        try (var context = start(args)) {
            System.out.println("BatchRecover demo: " + run(context));
        }
    }
}
