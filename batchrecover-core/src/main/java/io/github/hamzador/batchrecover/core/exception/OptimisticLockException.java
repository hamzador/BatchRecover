package io.github.hamzador.batchrecover.core.exception;

import java.util.UUID;

public class OptimisticLockException extends RuntimeException {
    public OptimisticLockException(UUID id) {
        super("Rejection version conflict: " + id);
    }
}
