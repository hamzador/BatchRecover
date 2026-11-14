package io.github.hamzador.batchrecover.starter;

import io.github.hamzador.batchrecover.core.codec.VersionedPayloadCodec;
import io.github.hamzador.batchrecover.core.domain.*;
import io.github.hamzador.batchrecover.core.port.RecoveryHandler;
import io.github.hamzador.batchrecover.core.service.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.JobInstance;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.scope.context.StepSynchronizationManager;
import org.springframework.batch.core.step.StepExecution;
import io.github.hamzador.batchrecover.springbatch.ClassifiedError;
import java.util.UUID;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class BatchRecoverStarterIT {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @Test
    void shouldAutoConfigureListenerAndRecoverThroughRegisteredHandler() {
        var source = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        var manager = new JdbcTransactionManager(source);
        var jdbc = new JdbcTemplate(source);
        new ResourceDatabasePopulator(
                new ClassPathResource("db/batchrecover/V1__create_batchrecover_tables.sql"),
                new ClassPathResource("db/batchrecover/V2__scope_worker_indexes.sql")).execute(source);
        jdbc.execute("CREATE TABLE starter_effect (id uuid PRIMARY KEY, payload text NOT NULL)");
        var codec = new VersionedPayloadCodec<String>("customer", 1, s -> s, Map.of(1, s -> s));
        RecoveryHandler<String> handler = new RecoveryHandler<>() {
            @Override public void validate(String item, RecoveryContext context) {}
            @Override public void replay(String item, RecoveryContext context) {
                jdbc.update("INSERT INTO starter_effect VALUES (?,?)", context.idempotencyKey(), item);
            }
        };
        var meters = new SimpleMeterRegistry();
        try {
            new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(BatchRecoverAutoConfiguration.class))
                    .withPropertyValues("batchrecover.enabled=true", "batchrecover.application-scope=starter-it")
                    .withBean(JdbcTransactionManager.class, () -> manager)
                    .withBean(RecoveryHandlerRegistry.Registration.class,
                            () -> new RecoveryHandlerRegistry.Registration<>("customer", codec, handler))
                    .withBean(SimpleMeterRegistry.class, () -> meters)
                    .run(context -> {
                        assertThat(context).hasNotFailed().hasSingleBean(BatchRecoverListenerFactory.class);
                        var service = context.getBean(RejectionService.class);
                        var listener = context.getBean(BatchRecoverListenerFactory.class)
                                .<String, String>create("customer", item -> "row:1", codec,
                                        failure -> new ClassifiedError("INVALID", ErrorCategory.BUSINESS));
                        var job = new JobExecution(2L, new JobInstance(1L, "import"),
                                new JobParametersBuilder().toJobParameters());
                        var step = new StepExecution(3L, "import", job);
                        StepSynchronizationManager.register(step);
                        try {
                            new TransactionTemplate(manager).executeWithoutResult(tx ->
                                    listener.onSkipInProcess("fixed", new IllegalArgumentException()));
                        } finally {
                            StepSynchronizationManager.close();
                        }
                        UUID id = jdbc.queryForObject("SELECT id FROM br_rejection", UUID.class);
                        var captured = service.get(id);
                        assertThat(captured.identity().applicationScope()).isEqualTo("starter-it");
                        assertThat(captured.maxAttempts()).isEqualTo(3);
                        service.queue(captured.id(), captured.version(), "operator", "replay");
                        assertThat(context.getBean(BatchRecoverWorker.class).runOnce()).isEqualTo(RecoveryResult.RECOVERED);
                        assertThat(jdbc.queryForObject("SELECT count(*) FROM starter_effect", Integer.class)).isEqualTo(1);
                        assertThat(service.get(captured.id()).status()).isEqualTo(RejectionStatus.RECOVERED);
                        assertThat(meters.get("batchrecover.worker.poll").tag("outcome", "RECOVERED").timer().count()).isEqualTo(1);
                    });
        } finally {
            meters.close();
        }
    }
}
