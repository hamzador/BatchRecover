package io.github.hamzador.batchrecover.core.exception;

import java.util.UUID;

public class CaptureConflictException extends RuntimeException {
    public CaptureConflictException(UUID id) {
        super("Conflicting original capture: " + id);
    }
}
