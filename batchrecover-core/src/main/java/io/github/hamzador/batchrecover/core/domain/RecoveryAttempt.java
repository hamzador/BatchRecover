package io.github.hamzador.batchrecover.core.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Attempt starts with RUNNING and can be finalized exactly once by its fencing token. */
public record RecoveryAttempt(UUID id, UUID rejectionId, int revision, int attemptNumber,
                              UUID leaseToken, Instant startedAt, Instant finishedAt,
                              AttemptOutcome outcome, String errorCode) {
    public RecoveryAttempt {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(rejectionId, "rejectionId");
        Objects.requireNonNull(leaseToken, "leaseToken");
        Objects.requireNonNull(startedAt, "startedAt");
        Objects.requireNonNull(outcome, "outcome");
        if (revision < 0 || attemptNumber < 1
                || (outcome == AttemptOutcome.RUNNING) != (finishedAt == null)
                || (finishedAt != null && finishedAt.isBefore(startedAt))) {
            throw new IllegalArgumentException("Invalid recovery attempt");
        }
        if (errorCode != null) {
            DomainChecks.text(errorCode, "errorCode", 128);
        }
    }
}
