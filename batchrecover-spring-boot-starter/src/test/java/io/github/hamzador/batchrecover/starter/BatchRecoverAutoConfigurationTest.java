package io.github.hamzador.batchrecover.starter;

import io.github.hamzador.batchrecover.core.port.*;
import io.github.hamzador.batchrecover.core.service.*;
import io.github.hamzador.batchrecover.storage.jdbc.JdbcRejectionRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.support.JdbcTransactionManager;

import javax.sql.DataSource;
import java.time.Duration;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class BatchRecoverAutoConfigurationTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(BatchRecoverAutoConfiguration.class));

    @Test
    void shouldDisableRecoveryByDefault() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(BatchRecoverProperties.class);
            assertThat(context.getBean(BatchRecoverProperties.class).enabled()).isFalse();
            assertThat(context).doesNotHaveBean(RecoveryEngine.class)
                    .doesNotHaveBean(RejectionService.class).doesNotHaveBean(BatchRecoverWorker.class)
                    .doesNotHaveBean(JdbcRejectionRepository.class);
        });
    }

    @Test
    void shouldBindPropertiesAndConfigureServicesWithoutDatabaseAccess() {
        DataSource source = mock(DataSource.class);
        enabled().withBean(JdbcTransactionManager.class, () -> new JdbcTransactionManager(source))
                .withPropertyValues("batchrecover.max-attempts=5", "batchrecover.lease-duration=45s",
                        "batchrecover.initial-retry-delay=250ms", "batchrecover.maximum-retry-delay=20s")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(JdbcRejectionRepository.class)
                            .hasSingleBean(RejectionService.class).hasSingleBean(RecoveryEngine.class)
                            .hasSingleBean(BatchRecoverWorker.class).hasSingleBean(BatchRecoverListenerFactory.class);
                    var props = context.getBean(BatchRecoverProperties.class);
                    assertThat(props.applicationScope()).isEqualTo("app");
                    assertThat(props.maxAttempts()).isEqualTo(5);
                    assertThat(props.leaseDuration()).isEqualTo(Duration.ofSeconds(45));
                    assertThat(props.initialRetryDelay()).isEqualTo(Duration.ofMillis(250));
                    assertThat(props.maximumRetryDelay()).isEqualTo(Duration.ofSeconds(20));
                });
        verifyNoInteractions(source);
    }

    @Test
    void shouldFailWhenEnabledWithoutJdbcTransactionManager() {
        enabled().run(context -> assertThat(context).hasFailed());
    }

    @Test
    void shouldFailWhenEnabledWithoutApplicationScope() {
        contextRunner.withPropertyValues("batchrecover.enabled=true")
                .withBean(JdbcTransactionManager.class, () -> new JdbcTransactionManager(mock(DataSource.class)))
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void shouldFailWhenTimingPropertiesAreInvalid() {
        enabled().withPropertyValues("batchrecover.initial-retry-delay=10s", "batchrecover.maximum-retry-delay=1s")
                .withBean(JdbcTransactionManager.class, () -> new JdbcTransactionManager(mock(DataSource.class)))
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void shouldFailWhenTransactionManagerIsAmbiguous() {
        enabled()
                .withBean("firstManager", JdbcTransactionManager.class, () -> new JdbcTransactionManager(mock(DataSource.class)))
                .withBean("secondManager", JdbcTransactionManager.class, () -> new JdbcTransactionManager(mock(DataSource.class)))
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void shouldRespectApplicationRepositoryOverrides() {
        RejectionRepository rejection = mock(RejectionRepository.class);
        RecoveryRepository recovery = mock(RecoveryRepository.class);
        enabled().withBean(RejectionRepository.class, () -> rejection)
                .withBean(RecoveryRepository.class, () -> recovery)
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(RecoveryEngine.class)
                            .doesNotHaveBean(JdbcRejectionRepository.class);
                    assertThat(context.getBean(RejectionRepository.class)).isSameAs(rejection);
                });
        verifyNoInteractions(rejection, recovery);
    }

    @Test
    void shouldNotRecordMetricsWhenExplicitlyDisabled() {
        RecoveryEngine engine = mock(RecoveryEngine.class);
        when(engine.runOnce()).thenReturn(io.github.hamzador.batchrecover.core.domain.RecoveryResult.NO_WORK);
        try (var registry = new SimpleMeterRegistry()) {
            enabled().withPropertyValues("batchrecover.metrics-enabled=false")
                    .withBean(RejectionRepository.class, () -> mock(RejectionRepository.class))
                    .withBean(RecoveryRepository.class, () -> mock(RecoveryRepository.class))
                    .withBean(RecoveryEngine.class, () -> engine)
                    .withBean(SimpleMeterRegistry.class, () -> registry)
                    .run(context -> {
                        context.getBean(BatchRecoverWorker.class).runOnce();
                        assertThat(registry.getMeters()).isEmpty();
                    });
        }
    }

    private ApplicationContextRunner enabled() {
        return contextRunner.withPropertyValues("batchrecover.enabled=true", "batchrecover.application-scope=app");
    }
}
