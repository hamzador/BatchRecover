package io.github.hamzador.batchrecover.core.exception;

/** Explicit business validation failure; correction is required if attempts remain. */
public class RecoveryValidationException extends RuntimeException {
    public RecoveryValidationException() {
        super("Recovery validation failed");
    }
}
