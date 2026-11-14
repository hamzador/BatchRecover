package io.github.hamzador.batchrecover.core.service;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;

class RetryPolicyTest {
    @Test
    void shouldCapExponentialBackoff() {
        var policy = new RetryPolicy(Duration.ofSeconds(2), Duration.ofSeconds(5));
        assertEquals(Duration.ofSeconds(2), policy.delayAfter(1));
        assertEquals(Duration.ofSeconds(4), policy.delayAfter(2));
        assertEquals(Duration.ofSeconds(5), policy.delayAfter(3));
        assertEquals(Duration.ofSeconds(5), policy.delayAfter(Integer.MAX_VALUE));
    }

    @Test
    void shouldRejectInvalidAttemptNumber() {
        var policy = new RetryPolicy(Duration.ofSeconds(1), Duration.ofSeconds(5));
        assertThrows(IllegalArgumentException.class, () -> policy.delayAfter(0));
    }
}
