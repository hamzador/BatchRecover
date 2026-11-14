package io.github.hamzador.batchrecover.core.domain;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ReplayPayloadTest {
    @Test
    void shouldRejectOversizedUtf8Payload() {
        assertThrows(IllegalArgumentException.class,
                () -> new ReplayPayload("customer", 1, "é".repeat(32_769)));
    }

    @Test
    void shouldNotExposeEncodedPayloadInToString() {
        var payload = new ReplayPayload("customer", 1, "private-content");
        assertFalse(payload.toString().contains("private-content"));
    }

    @Test
    void shouldRejectInvalidSchemaVersion() {
        assertThrows(IllegalArgumentException.class, () -> new ReplayPayload("customer", 0, "{}"));
    }
}
