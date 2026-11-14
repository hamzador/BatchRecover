package io.github.hamzador.batchrecover.core.exception;

import io.github.hamzador.batchrecover.core.domain.RecoveryFailure;
import java.util.Objects;

/** Signals that the business callback threw and its transaction must roll back. */
public final class HandlerExecutionException extends RuntimeException {
    private final RecoveryFailure failure;

    public HandlerExecutionException(RecoveryFailure failure) {
        super(Objects.requireNonNull(failure).name());
        this.failure = failure;
    }

    public RecoveryFailure failure() { return failure; }
}
