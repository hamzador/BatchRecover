package io.github.hamzador.batchrecover.core.service;

import java.time.Duration;
import java.util.Objects;

public record RecoverySettings(String applicationScope, Duration leaseDuration, RetryPolicy retryPolicy) {
    public RecoverySettings {
        if (applicationScope == null || applicationScope.isBlank() || applicationScope.length() > 128) {
            throw new IllegalArgumentException("Invalid application scope");
        }
        Objects.requireNonNull(leaseDuration);
        Objects.requireNonNull(retryPolicy);
        if (leaseDuration.compareTo(Duration.ofMillis(1)) < 0
                || leaseDuration.compareTo(Duration.ofDays(1)) > 0
                || retryPolicy.initialDelay().compareTo(Duration.ofMillis(1)) < 0
                || retryPolicy.maximumDelay().compareTo(Duration.ofDays(30)) > 0) {
            throw new IllegalArgumentException("Invalid worker timing settings");
        }
    }
}
