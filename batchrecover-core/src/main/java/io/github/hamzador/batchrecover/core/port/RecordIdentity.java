package io.github.hamzador.batchrecover.core.port;

public interface RecordIdentity<T> {
    String sourceOccurrenceId(T item);
}
