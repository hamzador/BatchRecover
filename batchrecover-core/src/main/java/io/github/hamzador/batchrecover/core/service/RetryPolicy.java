package io.github.hamzador.batchrecover.core.service;

import java.time.Duration;
import java.util.Objects;

/** Deterministic capped exponential delay; with optional equal jitter. */
public record RetryPolicy(Duration initialDelay, Duration maximumDelay) {
    public RetryPolicy {
        Objects.requireNonNull(initialDelay, "initialDelay");
        Objects.requireNonNull(maximumDelay, "maximumDelay");
        if (initialDelay.isNegative() || initialDelay.isZero()
                || maximumDelay.compareTo(initialDelay) < 0) {
            throw new IllegalArgumentException("Invalid retry delays");
        }
    }

    /** Uniform delay in [ceil(cap/2), cap] milliseconds; randomUnit is in [0,1). */
    public Duration delayWithJitter(int attemptNumber, double randomUnit) {
        if (!(randomUnit >= 0 && randomUnit < 1)) {
            throw new IllegalArgumentException("Invalid jitter sample");
        }
        long cap = delayAfter(attemptNumber).toMillis();
        if (cap < 1) throw new IllegalArgumentException("Delay must be at least 1 ms");
        long lower = cap - cap / 2;
        return Duration.ofMillis(lower + (long) (randomUnit * (cap - lower + 1)));
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
