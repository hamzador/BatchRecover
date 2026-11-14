package io.github.hamzador.batchrecover.core.domain;

import io.github.hamzador.batchrecover.core.exception.InvalidStatusTransitionException;

public enum RejectionStatus {
    PENDING, QUEUED, RUNNING, RECOVERED, DEAD, DISCARDED;

    public void requireTransitionTo(RejectionStatus target) {
        boolean allowed = switch (this) {
            case PENDING -> target == PENDING || target == QUEUED || target == DISCARDED;
            case QUEUED -> target == RUNNING || target == DISCARDED;
            case RUNNING -> target == RECOVERED || target == QUEUED
                    || target == DEAD || target == PENDING;
            case RECOVERED, DEAD, DISCARDED -> false;
        };
        if (!allowed) {
            throw new InvalidStatusTransitionException(this, target);
        }
    }
}
