package io.github.hamzador.batchrecover.core.domain;

public enum AttemptOutcome {
    RUNNING, RECOVERED, RETRYABLE_FAILURE, VALIDATION_FAILURE, EXPIRED, DEAD
}
