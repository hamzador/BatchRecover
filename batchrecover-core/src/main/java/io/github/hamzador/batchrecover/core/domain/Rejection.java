package io.github.hamzador.batchrecover.core.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Immutable snapshot. Mutations create a new version; persistence performs compare-and-set. */
public record Rejection(UUID id, RejectionIdentity identity, long jobExecutionId,
                        long stepExecutionId, String handlerId, String errorCode,
                        ErrorCategory category, String payloadType, RejectionStatus status,
                        int currentRevision, int attemptCount, int maxAttempts,
                        Instant nextAttemptAt, UUID leaseToken, Instant leaseExpiresAt,
                        long version, Instant createdAt, Instant updatedAt) {

    public Rejection {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        DomainChecks.nonNegative(jobExecutionId, "jobExecutionId");
        DomainChecks.nonNegative(stepExecutionId, "stepExecutionId");
        DomainChecks.text(handlerId, "handlerId", 128);
        DomainChecks.text(errorCode, "errorCode", 128);
        DomainChecks.text(payloadType, "payloadType", 128);
        if (currentRevision < 0 || version < 0 || maxAttempts < 1 || maxAttempts > 100
                || attemptCount < 0 || attemptCount > maxAttempts || updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("Invalid rejection counters or timestamps");
        }
        if ((status == RejectionStatus.QUEUED) != (nextAttemptAt != null)
                || (status == RejectionStatus.RUNNING) != (leaseToken != null)
                || (status == RejectionStatus.RUNNING) != (leaseExpiresAt != null)) {
            throw new IllegalArgumentException("Invalid scheduling or lease state");
        }
        if ((status == RejectionStatus.PENDING || status == RejectionStatus.QUEUED)
                && attemptCount >= maxAttempts) {
            throw new IllegalArgumentException("Attempt budget exhausted");
        }
    }

    public static Rejection capture(UUID id, CaptureRequest request, Instant now) {
        return new Rejection(id, request.identity(), request.jobExecutionId(),
                request.stepExecutionId(), request.handlerId(), request.errorCode(),
                request.category(), request.payload().typeId(), RejectionStatus.PENDING,
                0, 0, request.maxAttempts(), null, null, null, 0, now, now);
    }

    public Rejection correct(Instant now) {
        status.requireTransitionTo(RejectionStatus.PENDING);
        if (status != RejectionStatus.PENDING) {
            throw new io.github.hamzador.batchrecover.core.exception.InvalidStatusTransitionException(status, RejectionStatus.PENDING);
        }
        return change(RejectionStatus.PENDING, Math.incrementExact(currentRevision), null, now);
    }

    public Rejection queue(Instant now) {
        requirePending(RejectionStatus.QUEUED);
        return change(RejectionStatus.QUEUED, currentRevision, now, now);
    }

    public Rejection discard(Instant now) {
        status.requireTransitionTo(RejectionStatus.DISCARDED);
        return change(RejectionStatus.DISCARDED, currentRevision, null, now);
    }

    private void requirePending(RejectionStatus target) {
        if (status != RejectionStatus.PENDING) {
            throw new io.github.hamzador.batchrecover.core.exception.InvalidStatusTransitionException(status, target);
        }
        status.requireTransitionTo(target);
    }

    private Rejection change(RejectionStatus target, int revision, Instant due, Instant now) {
        return new Rejection(id, identity, jobExecutionId, stepExecutionId, handlerId,
                errorCode, category, payloadType, target, revision, attemptCount, maxAttempts,
                due, null, null, Math.incrementExact(version), createdAt, now);
    }
}
