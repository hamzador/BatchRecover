package io.github.hamzador.batchrecover.starter;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class BatchRecoverAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(BatchRecoverAutoConfiguration.class));

    @Test
    void shouldDisableRecoveryByDefault() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(BatchRecoverProperties.class);
            assertThat(context.getBean(BatchRecoverProperties.class).enabled()).isFalse();
        });
    }

    @Test
    void shouldBindExplicitActivationWithoutStartingRecovery() {
        contextRunner.withPropertyValues("batchrecover.enabled=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(BatchRecoverProperties.class);
                    assertThat(context.getBean(BatchRecoverProperties.class).enabled()).isTrue();
                    assertThat(context).doesNotHaveBean(javax.sql.DataSource.class);
                });
    }
}
