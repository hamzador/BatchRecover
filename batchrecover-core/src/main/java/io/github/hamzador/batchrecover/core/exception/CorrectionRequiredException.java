package io.github.hamzador.batchrecover.core.exception;

import java.util.UUID;

/** A business validation failure requires a new immutable revision before another queue request. */
public class CorrectionRequiredException extends RuntimeException {
    public CorrectionRequiredException(UUID id) {
        super("A new payload revision is required before queueing rejection: " + id);
    }
}
