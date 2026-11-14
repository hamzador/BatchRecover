package io.github.hamzador.batchrecover.core.exception;

import io.github.hamzador.batchrecover.core.domain.RejectionStatus;

public class InvalidStatusTransitionException extends RuntimeException {
    public InvalidStatusTransitionException(RejectionStatus from, RejectionStatus to) {
        super("Invalid rejection transition: " + from + " -> " + to);
    }
}
