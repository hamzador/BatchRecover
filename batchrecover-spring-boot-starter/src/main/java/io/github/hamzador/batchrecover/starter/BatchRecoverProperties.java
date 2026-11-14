package io.github.hamzador.batchrecover.starter;

import io.github.hamzador.batchrecover.core.service.RecoverySettings;
import io.github.hamzador.batchrecover.core.service.RetryPolicy;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import java.time.Duration;
import io.github.hamzador.batchrecover.storage.memory.MemoryStorageLimits;
import java.util.Objects;

/** Explicitly opt in to recovery. Neither worker scheduling nor schema changes are automatic. */
@ConfigurationProperties("batchrecover")
public record BatchRecoverProperties(
        @DefaultValue("false") boolean enabled,
        String applicationScope,
        @DefaultValue("3") int maxAttempts,
        @DefaultValue("30s") Duration leaseDuration,
        @DefaultValue("1s") Duration initialRetryDelay,
        @DefaultValue("1m") Duration maximumRetryDelay,
        @DefaultValue("true") boolean metricsEnabled,
        @DefaultValue Storage storage) {

    public BatchRecoverProperties {
        Objects.requireNonNull(storage);
        if (maxAttempts < 1 || maxAttempts > 100) {
            throw new IllegalArgumentException("batchrecover.max-attempts must be between 1 and 100");
        }
        if (enabled) {
            new RecoverySettings(applicationScope, leaseDuration,
                    new RetryPolicy(initialRetryDelay, maximumRetryDelay));
        }
    }

    public enum StorageType { JDBC, MEMORY, CUSTOM }

    public record Storage(@DefaultValue("jdbc") StorageType type,
                          @DefaultValue("1000") int maxRecords,
                          @DefaultValue("8") int maxRevisionsPerRecord,
                          @DefaultValue("16777216") long maxPayloadBytes,
                          @DefaultValue("auto") String dialect) {
        public Storage {
            Objects.requireNonNull(type);
            if (dialect == null || !dialect.matches("[A-Za-z0-9_-]{1,64}")) {
                throw new IllegalArgumentException("Invalid SQL dialect name");
            }
            new MemoryStorageLimits(maxRecords, maxRevisionsPerRecord, maxPayloadBytes);
        }

        MemoryStorageLimits memoryLimits() {
            return new MemoryStorageLimits(maxRecords, maxRevisionsPerRecord, maxPayloadBytes);
        }
    }

    RecoverySettings workerSettings() {
        return new RecoverySettings(applicationScope, leaseDuration,
                new RetryPolicy(initialRetryDelay, maximumRetryDelay));
    }
}
