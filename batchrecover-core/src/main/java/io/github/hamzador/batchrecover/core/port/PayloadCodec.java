package io.github.hamzador.batchrecover.core.port;

/** Explicit approved DTO codec; keep logical type IDs stable and support retained schema versions.
 * @param <T> payload type
 */
public interface PayloadCodec<T> {
    /** Stable logical identifier, not a Java class name. */
    String typeId();
    /** Version emitted by the encoder. */
    int schemaVersion();
    /** Encode only necessary approved data, never credentials. */
    String encode(T item);
    /** Decode the supplied version; reject unsupported or malformed data. */
    T decode(String payload, int version);
}
