package io.github.hamzador.batchrecover.core.service;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;

class RecoveryTimingTest {
    private static final Duration MS = Duration.ofMillis(1);
    private static final RetryPolicy RETRY = new RetryPolicy(MS, Duration.ofDays(30));

    @Test
    void shouldValidateAllIndependentTimingLimits() {
        assertDoesNotThrow(() -> new RecoverySettings("a".repeat(128), MS, RETRY));
        assertDoesNotThrow(() -> new RecoverySettings("app", Duration.ofDays(1), RETRY));
        assertThrows(IllegalArgumentException.class, () -> new RecoverySettings(null, MS, RETRY));
        assertThrows(IllegalArgumentException.class, () -> new RecoverySettings(" ", MS, RETRY));
        assertThrows(IllegalArgumentException.class, () -> new RecoverySettings("a".repeat(129), MS, RETRY));
        assertThrows(IllegalArgumentException.class, () -> new RecoverySettings("app", MS.minusNanos(1), RETRY));
        assertThrows(IllegalArgumentException.class, () -> new RecoverySettings("app", Duration.ofDays(1).plusNanos(1), RETRY));
        assertThrows(IllegalArgumentException.class, () -> new RecoverySettings("app", MS, new RetryPolicy(MS.minusNanos(1), MS)));
        assertThrows(IllegalArgumentException.class, () -> new RecoverySettings("app", MS, new RetryPolicy(MS, Duration.ofDays(30).plusNanos(1))));
    }

    @Test
    void shouldCapExponentialDelayWithoutOverflow() {
        var retry = new RetryPolicy(Duration.ofMillis(3), Duration.ofMillis(10));
        assertEquals(Duration.ofMillis(3), retry.delayAfter(1));
        assertEquals(Duration.ofMillis(6), retry.delayAfter(2));
        assertEquals(Duration.ofMillis(10), retry.delayAfter(3));
        assertEquals(Duration.ofMillis(10), retry.delayAfter(Integer.MAX_VALUE));
        var exact = new RetryPolicy(MS, Duration.ofMillis(4));
        assertEquals(Duration.ofMillis(4), exact.delayAfter(3));
        assertEquals(Duration.ofMillis(4), exact.delayAfter(4));
        assertEquals(MS, new RetryPolicy(MS, MS).delayAfter(Integer.MAX_VALUE));
        assertThrows(IllegalArgumentException.class, () -> retry.delayAfter(0));
    }

    @Test
    void shouldValidateDelaysAndExactJitterBounds() {
        assertThrows(IllegalArgumentException.class, () -> new RetryPolicy(Duration.ZERO, MS));
        assertThrows(IllegalArgumentException.class, () -> new RetryPolicy(Duration.ofMillis(-1), MS));
        assertThrows(IllegalArgumentException.class, () -> new RetryPolicy(MS, Duration.ZERO));
        var retry = new RetryPolicy(Duration.ofMillis(5), Duration.ofMillis(10));
        assertEquals(Duration.ofMillis(3), retry.delayWithJitter(1, 0));
        assertEquals(Duration.ofMillis(4), retry.delayWithJitter(1, 0.5));
        assertEquals(Duration.ofMillis(5), retry.delayWithJitter(1, 0.99));
        assertEquals(Duration.ofMillis(5), retry.delayWithJitter(2, 0));
        assertEquals(Duration.ofMillis(10), retry.delayWithJitter(2, 0.99));
        assertEquals(MS, RETRY.delayWithJitter(1, 0.99));
        for (double sample : new double[]{-0.01, 1, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> retry.delayWithJitter(1, sample));
        }
        assertThrows(IllegalArgumentException.class, () -> new RetryPolicy(Duration.ofNanos(1), MS).delayWithJitter(1, 0));
    }
}
