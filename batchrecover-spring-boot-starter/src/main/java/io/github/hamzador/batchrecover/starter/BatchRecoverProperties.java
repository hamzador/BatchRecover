package io.github.hamzador.batchrecover.starter;

import io.github.hamzador.batchrecover.core.service.RecoverySettings;
import io.github.hamzador.batchrecover.core.service.RetryPolicy;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import java.time.Duration;

/** Explicitly opt in to recovery. Neither worker scheduling nor schema changes are automatic. */
@ConfigurationProperties("batchrecover")
public record BatchRecoverProperties(
        @DefaultValue("false") boolean enabled,
        String applicationScope,
        @DefaultValue("3") int maxAttempts,
        @DefaultValue("30s") Duration leaseDuration,
        @DefaultValue("1s") Duration initialRetryDelay,
        @DefaultValue("1m") Duration maximumRetryDelay,
        @DefaultValue("true") boolean metricsEnabled) {

    public BatchRecoverProperties {
        if (maxAttempts < 1 || maxAttempts > 100) {
            throw new IllegalArgumentException("batchrecover.max-attempts must be between 1 and 100");
        }
        if (enabled) {
            new RecoverySettings(applicationScope, leaseDuration,
                    new RetryPolicy(initialRetryDelay, maximumRetryDelay));
        }
    }

    RecoverySettings workerSettings() {
        return new RecoverySettings(applicationScope, leaseDuration,
                new RetryPolicy(initialRetryDelay, maximumRetryDelay));
    }
}
