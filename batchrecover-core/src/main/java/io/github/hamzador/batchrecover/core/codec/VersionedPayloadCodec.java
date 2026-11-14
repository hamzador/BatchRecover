package io.github.hamzador.batchrecover.core.codec;

import io.github.hamzador.batchrecover.core.domain.ReplayPayload;
import io.github.hamzador.batchrecover.core.port.PayloadCodec;

import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/** Explicit encoder and version-specific decoders. No reflection or dynamic class loading. */
public final class VersionedPayloadCodec<T> implements PayloadCodec<T> {
    private final String typeId;
    private final int version;
    private final Function<T, String> encoder;
    private final Map<Integer, Function<String, T>> decoders;

    public VersionedPayloadCodec(String typeId, int version, Function<T, String> encoder,
                                 Map<Integer, Function<String, T>> decoders) {
        this.typeId = new ReplayPayload(typeId, version, "{}").typeId();
        this.version = version;
        this.encoder = Objects.requireNonNull(encoder);
        this.decoders = Map.copyOf(decoders);
        if (!this.decoders.containsKey(version) || this.decoders.keySet().stream().anyMatch(v -> v < 1)) {
            throw new IllegalArgumentException("A decoder for the current schema and positive versions is required");
        }
    }

    @Override public String typeId() { return typeId; }
    @Override public int schemaVersion() { return version; }

    @Override
    public String encode(T item) {
        return new ReplayPayload(typeId, version,
                encoder.apply(Objects.requireNonNull(item))).encoded();
    }

    @Override
    public T decode(String payload, int version) {
        new ReplayPayload(typeId, version, payload);
        var decoder = decoders.get(version);
        if (decoder == null) {
            throw new IllegalArgumentException("Unsupported payload schema version: " + version);
        }
        return Objects.requireNonNull(decoder.apply(payload), "Decoded payload");
    }
}
