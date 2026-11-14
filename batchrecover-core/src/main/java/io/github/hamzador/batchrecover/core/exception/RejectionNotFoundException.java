package io.github.hamzador.batchrecover.core.exception;

import java.util.UUID;

public class RejectionNotFoundException extends RuntimeException {
    public RejectionNotFoundException(UUID id) {
        super("Rejection not found: " + id);
    }
}
