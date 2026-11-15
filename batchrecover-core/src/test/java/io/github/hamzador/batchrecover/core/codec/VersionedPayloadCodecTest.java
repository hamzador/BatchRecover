package io.github.hamzador.batchrecover.core.codec;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class VersionedPayloadCodecTest {
    @Test
    void shouldEncodeCurrentVersionAndDecodeLegacyVersion() {
        var codec = new VersionedPayloadCodec<String>("customer", 2, value -> "v2:" + value,
                Map.of(1, value -> value, 2, value -> value.substring(3)));
        assertEquals("v2:example", codec.encode("example"));
        assertEquals("example", codec.decode("example", 1));
        assertEquals("example", codec.decode("v2:example", 2));
    }

    @Test
    void shouldRejectUnknownSchemaVersion() {
        var codec = new VersionedPayloadCodec<String>("customer", 1, value -> value,
                Map.of(1, value -> value));
        assertThrows(IllegalArgumentException.class, () -> codec.decode("example", 2));
    }

    @Test
    void shouldRequireCurrentVersionDecoder() {
        assertThrows(IllegalArgumentException.class, () ->
                new VersionedPayloadCodec<String>("customer", 2, value -> value, Map.of(1, value -> value)));
    }
    @Test
    void shouldRejectNegativeDecoderVersionAndInvalidEncoderOutput() {
        assertThrows(IllegalArgumentException.class, () ->
                new VersionedPayloadCodec<String>("customer", 1, v -> v, Map.of(0, v -> v, 1, v -> v)));
        var codec = new VersionedPayloadCodec<String>("customer", 1, v -> " ", Map.of(1, v -> null));
        assertEquals("customer", codec.typeId());
        assertEquals(1, codec.schemaVersion());
        assertThrows(IllegalArgumentException.class, () -> codec.encode("bad"));
        assertThrows(NullPointerException.class, () -> codec.encode(null));
        assertThrows(NullPointerException.class, () -> codec.decode("bad", 1));
        assertThrows(IllegalArgumentException.class, () -> codec.decode(" ", 1));
    }
}
