package io.github.hamzador.batchrecover.core.port;

import io.github.hamzador.batchrecover.core.domain.RecoveryContext;

/** Synchronous effects must join the worker's same physical JDBC transaction. */
public interface RecoveryHandler<T> {
    void validate(T item, RecoveryContext context);
    void replay(T item, RecoveryContext context);
}
