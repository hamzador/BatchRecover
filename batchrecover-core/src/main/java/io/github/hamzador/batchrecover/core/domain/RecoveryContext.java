package io.github.hamzador.batchrecover.core.domain;

import java.util.UUID;

/** The rejection ID is a stable idempotency key across attempts and corrections. */
public record RecoveryContext(UUID rejectionId, UUID idempotencyKey, RejectionIdentity identity,
                              int revision, int attemptNumber) {
    public static RecoveryContext from(Rejection rejection) {
        return new RecoveryContext(rejection.id(), rejection.id(), rejection.identity(),
                rejection.currentRevision(), rejection.attemptCount());
    }
}
