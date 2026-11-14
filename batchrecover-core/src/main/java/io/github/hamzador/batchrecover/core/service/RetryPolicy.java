package io.github.hamzador.batchrecover.core.service;

import java.time.Duration;
import java.util.Objects;

/** Deterministic capped exponential delay; engine will supply scheduling and jitter. */
public record RetryPolicy(Duration initialDelay, Duration maximumDelay) {
    public RetryPolicy {
        Objects.requireNonNull(initialDelay, "initialDelay");
        Objects.requireNonNull(maximumDelay, "maximumDelay");
        if (initialDelay.isNegative() || initialDelay.isZero()
                || maximumDelay.compareTo(initialDelay) < 0) {
            throw new IllegalArgumentException("Invalid retry delays");
        }
    }

    public Duration delayAfter(int attemptNumber) {
        if (attemptNumber < 1) {
            throw new IllegalArgumentException("attemptNumber must be positive");
        }
        Duration delay = initialDelay;
        for (int i = 1; i < attemptNumber && delay.compareTo(maximumDelay) < 0; i++) {
            if (delay.compareTo(maximumDelay.dividedBy(2)) > 0) {
                return maximumDelay;
            }
            delay = delay.multipliedBy(2);
        }
        return delay;
    }
}
