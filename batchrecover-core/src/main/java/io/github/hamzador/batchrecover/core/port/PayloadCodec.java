package io.github.hamzador.batchrecover.core.port;

public interface PayloadCodec<T> {
    String typeId();
    int schemaVersion();
    String encode(T item);
    T decode(String payload, int version);
}
