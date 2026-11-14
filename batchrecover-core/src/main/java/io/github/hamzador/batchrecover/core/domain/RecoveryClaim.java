package io.github.hamzador.batchrecover.core.domain;

import java.util.Objects;
import java.util.UUID;

/** A fencing token for one specific attempt, not the stable operation identity. */
public record RecoveryClaim(UUID rejectionId, UUID leaseToken, String applicationScope,
                            int revision, int attemptNumber) {
    public RecoveryClaim {
        Objects.requireNonNull(rejectionId);
        Objects.requireNonNull(leaseToken);
        DomainChecks.text(applicationScope, "applicationScope", 128);
        if (revision < 0 || attemptNumber < 1) {
            throw new IllegalArgumentException("Invalid claim counters");
        }
    }

    public static RecoveryClaim from(Rejection rejection) {
        if (rejection.status() != RejectionStatus.RUNNING) {
            throw new IllegalArgumentException("Rejection is not running");
        }
        return new RecoveryClaim(rejection.id(), rejection.leaseToken(),
                rejection.identity().applicationScope(), rejection.currentRevision(), rejection.attemptCount());
    }

    public boolean matches(Rejection rejection) {
        return rejection.status() == RejectionStatus.RUNNING
                && equals(from(rejection));
    }
}
