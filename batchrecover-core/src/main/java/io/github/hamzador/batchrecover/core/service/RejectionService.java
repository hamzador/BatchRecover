package io.github.hamzador.batchrecover.core.service;

import io.github.hamzador.batchrecover.core.domain.*;
import io.github.hamzador.batchrecover.core.exception.OptimisticLockException;
import io.github.hamzador.batchrecover.core.exception.RejectionNotFoundException;
import io.github.hamzador.batchrecover.core.port.RejectionRepository;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Local trusted API; authorization, sanitization and payload semantics belong to the caller. */
public class RejectionService {
    private final RejectionRepository repository;
    private final Clock clock;

    public RejectionService(RejectionRepository repository, Clock clock) {
        this.repository = Objects.requireNonNull(repository);
        this.clock = Objects.requireNonNull(clock);
    }

    /** Capture explicitly or return the existing matching rejection. */
    public Rejection capture(CaptureRequest request) {
        return capture(request, false);
    }

    /** Join the existing compatible physical transaction; fail if unsupported. */
    public Rejection captureInExistingTransaction(CaptureRequest request) {
        return capture(request, true);
    }

    private Rejection capture(CaptureRequest request, boolean existingTransactionRequired) {
        Instant now = now();
        Rejection rejection = Rejection.capture(UUID.randomUUID(), request, now);
        PayloadRevision original = new PayloadRevision(rejection.id(), 0, request.payload(),
                "system", "capture", now);
        AuditEvent audit = audit(rejection, null, AuditAction.CAPTURED, "system", "capture");
        return existingTransactionRequired
                ? repository.captureInExistingTransaction(rejection, original, audit)
                : repository.capture(rejection, original, audit);
    }

    /** Load the current snapshot; missing IDs raise RejectionNotFoundException. */
    public Rejection get(UUID id) {
        return repository.findById(Objects.requireNonNull(id))
                .orElseThrow(() -> new RejectionNotFoundException(id));
    }

    /** Append an approved revision while PENDING, preserving the original. Use the returned version for queueing. */
    public Rejection correct(UUID id, long expectedVersion, ReplayPayload payload,
                             String actor, String reason) {
        Rejection current = loadVersion(id, expectedVersion);
        if (!current.payloadType().equals(payload.typeId())) {
            throw new IllegalArgumentException("Payload type cannot change");
        }
        Rejection updated = current.correct(mutationTime(current));
        PayloadRevision revision = new PayloadRevision(id, updated.currentRevision(),
                payload, actor, reason, updated.updatedAt());
        return repository.save(updated, expectedVersion, Optional.of(revision),
                audit(updated, current.status(), AuditAction.CORRECTED, actor, reason));
    }

    /** Queue an approved revision with a fresh version; does not execute a handler. */
    public Rejection queue(UUID id, long expectedVersion, String actor, String reason) {
        Rejection current = loadVersion(id, expectedVersion);
        Rejection updated = current.queue(mutationTime(current));
        return repository.save(updated, expectedVersion, Optional.empty(),
                audit(updated, current.status(), AuditAction.QUEUED, actor, reason));
    }

    /** Discard pending or queued work without deleting its history. */
    public Rejection discard(UUID id, long expectedVersion, String actor, String reason) {
        Rejection current = loadVersion(id, expectedVersion);
        Rejection updated = current.discard(mutationTime(current));
        return repository.save(updated, expectedVersion, Optional.empty(),
                audit(updated, current.status(), AuditAction.DISCARDED, actor, reason));
    }

    private Rejection loadVersion(UUID id, long version) {
        Rejection rejection = get(id);
        if (rejection.version() != version) {
            throw new OptimisticLockException(id);
        }
        return rejection;
    }

    private Instant mutationTime(Rejection current) {
        Instant time = now();
        return time.isBefore(current.updatedAt()) ? current.updatedAt() : time;
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private static AuditEvent audit(Rejection rejection, RejectionStatus previous,
                                    AuditAction action, String actor, String reason) {
        return new AuditEvent(UUID.randomUUID(), rejection.id(), rejection.version(), action,
                actor, reason, previous, rejection.status(), rejection.updatedAt());
    }
}
