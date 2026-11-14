package io.github.hamzador.batchrecover.storage.memory;

import io.github.hamzador.batchrecover.core.domain.*;
import io.github.hamzador.batchrecover.core.exception.*;
import io.github.hamzador.batchrecover.core.port.RecoveryStorage;
import io.github.hamzador.batchrecover.core.port.StorageCapabilities;

import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.IntFunction;

/**
 * Bounded, single-JVM storage. All metadata transitions are synchronized; callbacks run outside
 * the monitor with a pinned claim. No durability or transaction rollback of business effects.
 */
public final class InMemoryRecoveryStorage implements RecoveryStorage {
    private final Clock clock;
    private final MemoryStorageLimits limits;
    private final Map<UUID, Entry> records = new HashMap<>();
    private final Map<RejectionIdentity, UUID> identities = new HashMap<>();
    private final Set<UUID> executing = new HashSet<>();
    private long payloadBytes;

    public InMemoryRecoveryStorage(Clock clock, MemoryStorageLimits limits) {
        this.clock = Objects.requireNonNull(clock);
        this.limits = Objects.requireNonNull(limits);
    }

    @Override public StorageCapabilities capabilities() { return StorageCapabilities.MEMORY; }

    @Override
    public synchronized Rejection capture(Rejection r, PayloadRevision original, AuditEvent audit) {
        validateAudit(r, audit);
        if (r.status() != RejectionStatus.PENDING || r.version() != 0 || r.currentRevision() != 0
                || r.attemptCount() != 0 || original.revision() != 0
                || !original.rejectionId().equals(r.id())
                || !original.payload().typeId().equals(r.payloadType()) || audit.action() != AuditAction.CAPTURED) {
            throw new IllegalArgumentException("Invalid initial capture");
        }
        var existing = records.get(identities.get(r.identity()));
        if (existing != null) {
            if (!existing.revisions.getFirst().payload().equals(original.payload())
                    || !existing.rejection.handlerId().equals(r.handlerId())) {
                throw new CaptureConflictException(existing.rejection.id());
            }
            return existing.rejection;
        }
        if (records.containsKey(r.id())) throw new IllegalArgumentException("Duplicate rejection ID");
        if (records.size() >= limits.maxRecords()) throw new StorageCapacityExceededException("max-records");
        long bytes = bytes(original);
        requirePayloadCapacity(bytes);
        var entry = new Entry(r);
        entry.revisions.add(original);
        entry.audit.add(audit);
        records.put(r.id(), entry);
        identities.put(r.identity(), r.id());
        payloadBytes += bytes;
        return r;
    }

    @Override
    public synchronized List<Rejection> findPending(String scope, int limit) {
        validateScope(scope);
        if (limit < 1 || limit > 1000) throw new IllegalArgumentException("Invalid query limit");
        return records.values().stream().map(e -> e.rejection)
                .filter(r -> r.status() == RejectionStatus.PENDING && r.identity().applicationScope().equals(scope))
                .sorted(Comparator.comparing(Rejection::createdAt).thenComparing(Rejection::id))
                .limit(limit).toList();
    }

    @Override
    public synchronized Optional<Rejection> findById(UUID id) {
        return Optional.ofNullable(records.get(id)).map(e -> e.rejection);
    }

    @Override
    public synchronized List<PayloadRevision> findRevisions(UUID id) {
        var e = records.get(id);
        return e == null ? List.of() : List.copyOf(e.revisions);
    }

    @Override
    public synchronized List<AuditEvent> findAuditEvents(UUID id) {
        var e = records.get(id);
        return e == null ? List.of() : List.copyOf(e.audit);
    }

    @Override
    public synchronized List<RecoveryAttempt> findAttempts(UUID id) {
        var e = records.get(id);
        return e == null ? List.of() : List.copyOf(e.attempts);
    }

    @Override
    public synchronized Rejection save(Rejection updated, long expectedVersion,
                                       Optional<PayloadRevision> revision, AuditEvent audit) {
        validateAudit(updated, audit);
        var e = records.get(updated.id());
        if (e == null || e.rejection.version() != expectedVersion
                || e.rejection.status() != audit.previousStatus()) {
            throw new OptimisticLockException(updated.id());
        }
        var current = e.rejection;
        Rejection expected = switch (audit.action()) {
            case CORRECTED -> current.correct(updated.updatedAt());
            case QUEUED -> current.queue(updated.updatedAt());
            case DISCARDED -> current.discard(updated.updatedAt());
            default -> throw new IllegalArgumentException("Unsupported mutation");
        };
        if (!expected.equals(updated) || (audit.action() == AuditAction.CORRECTED) != revision.isPresent()) {
            throw new IllegalArgumentException("Invalid mutation");
        }
        if (audit.action() == AuditAction.QUEUED && e.attempts.stream().anyMatch(a ->
                a.revision() == current.currentRevision() && a.outcome() == AttemptOutcome.VALIDATION_FAILURE)) {
            throw new CorrectionRequiredException(updated.id());
        }
        if (revision.isPresent()) {
            var p = revision.orElseThrow();
            if (!p.rejectionId().equals(updated.id()) || p.revision() != updated.currentRevision()
                    || !p.payload().typeId().equals(updated.payloadType())) {
                throw new IllegalArgumentException("Invalid payload revision");
            }
            if (e.revisions.size() >= limits.maxRevisionsPerRecord()) {
                throw new StorageCapacityExceededException("max-revisions-per-record");
            }
            long size = bytes(p);
            requirePayloadCapacity(size);
            e.revisions.add(p);
            payloadBytes += size;
        }
        e.rejection = updated;
        e.audit.add(audit);
        return updated;
    }

    @Override
    public synchronized Optional<RecoveryClaim> claimNext(String scope, Duration lease) {
        validateScope(scope);
        validateDuration(lease, Duration.ofDays(1));
        Instant now = now();
        return records.values().stream()
                .filter(e -> e.rejection.identity().applicationScope().equals(scope))
                .filter(e -> e.rejection.status() == RejectionStatus.QUEUED)
                .filter(e -> !e.rejection.nextAttemptAt().isAfter(now))
                .min(Comparator.comparing((Entry e) -> e.rejection.nextAttemptAt())
                        .thenComparing(e -> e.rejection.id()))
                .map(e -> {
                    var previous = e.rejection;
                    Instant time = transitionTime(previous);
                    var next = previous.claim(UUID.randomUUID(), time.plusMillis(lease.toMillis()), time);
                    var attempt = new RecoveryAttempt(UUID.randomUUID(), next.id(), next.currentRevision(),
                            next.attemptCount(), next.leaseToken(), time, null, AttemptOutcome.RUNNING, null);
                    var audit = workerAudit(previous, next, AuditAction.CLAIMED, "CLAIMED");
                    e.attempts.add(attempt);
                    e.rejection = next;
                    e.audit.add(audit);
                    return RecoveryClaim.from(next);
                });
    }

    @Override
    public boolean execute(RecoveryClaim claim, Consumer<RecoveryWork> callback) {
        Objects.requireNonNull(callback);
        RecoveryWork work;
        synchronized (this) {
            var e = matching(claim);
            if (e == null || executing.contains(claim.rejectionId())
                    || !e.rejection.leaseExpiresAt().isAfter(now())) return false;
            work = new RecoveryWork(e.rejection, e.revisions.get(e.rejection.currentRevision()).payload());
            executing.add(claim.rejectionId());
        }
        try {
            callback.accept(work);
            synchronized (this) {
                var e = Objects.requireNonNull(matching(claim));
                var next = e.rejection.recovered(transitionTime(e.rejection));
                finish(e, next, AttemptOutcome.RECOVERED, AuditAction.RECOVERED, null);
                return true;
            }
        } finally {
            synchronized (this) {
                executing.remove(claim.rejectionId());
            }
        }
    }

    @Override
    public synchronized Optional<RejectionStatus> fail(RecoveryClaim claim, RecoveryFailure failure, Duration delay) {
        if (failure == RecoveryFailure.LEASE_EXPIRED) throw new IllegalArgumentException("Use the lease reaper");
        validateDuration(delay, Duration.ofDays(30));
        var e = matching(claim);
        if (e == null || executing.contains(claim.rejectionId())) return Optional.empty();
        return Optional.of(finishFailure(e, failure, delay));
    }

    @Override
    public synchronized boolean reapExpired(String scope, IntFunction<Duration> delayForAttempt) {
        validateScope(scope);
        Objects.requireNonNull(delayForAttempt);
        Instant now = now();
        var expired = records.values().stream()
                .filter(e -> e.rejection.identity().applicationScope().equals(scope))
                .filter(e -> e.rejection.status() == RejectionStatus.RUNNING)
                .filter(e -> !executing.contains(e.rejection.id()))
                .filter(e -> !e.rejection.leaseExpiresAt().isAfter(now))
                .min(Comparator.comparing((Entry e) -> e.rejection.leaseExpiresAt())
                        .thenComparing(e -> e.rejection.id()));
        if (expired.isEmpty()) return false;
        var e = expired.orElseThrow();
        var delay = delayForAttempt.apply(e.rejection.attemptCount());
        validateDuration(delay, Duration.ofDays(30));
        finishFailure(e, RecoveryFailure.LEASE_EXPIRED, delay);
        return true;
    }

    private RejectionStatus finishFailure(Entry e, RecoveryFailure failure, Duration delay) {
        var next = e.rejection.failed(failure, delay, transitionTime(e.rejection));
        var status = next.status();
        var outcome = status == RejectionStatus.DEAD ? AttemptOutcome.DEAD
                : failure == RecoveryFailure.LEASE_EXPIRED ? AttemptOutcome.EXPIRED
                : failure == RecoveryFailure.VALIDATION_FAILED ? AttemptOutcome.VALIDATION_FAILURE
                : AttemptOutcome.RETRYABLE_FAILURE;
        var action = status == RejectionStatus.DEAD ? AuditAction.DEAD
                : status == RejectionStatus.PENDING ? AuditAction.VALIDATION_FAILED : AuditAction.RETRY_SCHEDULED;
        finish(e, next, outcome, action, failure.name());
        return status;
    }

    private void finish(Entry e, Rejection next, AttemptOutcome outcome, AuditAction action, String code) {
        var previous = e.rejection;
        var attempt = e.attempts.getLast();
        if (attempt.outcome() != AttemptOutcome.RUNNING || !attempt.leaseToken().equals(previous.leaseToken())) {
            throw new IllegalStateException("Attempt was not active");
        }
        var finished = new RecoveryAttempt(attempt.id(), attempt.rejectionId(), attempt.revision(),
                attempt.attemptNumber(), attempt.leaseToken(), attempt.startedAt(), next.updatedAt(), outcome, code);
        var audit = workerAudit(previous, next, action, code == null ? "RECOVERED" : code);
        e.attempts.set(e.attempts.size() - 1, finished);
        e.rejection = next;
        e.audit.add(audit);
    }

    private Entry matching(RecoveryClaim claim) {
        var e = records.get(claim.rejectionId());
        return e != null && claim.matches(e.rejection) ? e : null;
    }

    private static AuditEvent workerAudit(Rejection previous, Rejection next, AuditAction action, String reason) {
        return new AuditEvent(UUID.randomUUID(), next.id(), next.version(), action,
                "recovery-engine", reason, previous.status(), next.status(), next.updatedAt());
    }

    private static void validateAudit(Rejection r, AuditEvent audit) {
        if (!r.id().equals(audit.rejectionId()) || r.version() != audit.version()
                || r.status() != audit.newStatus() || !r.updatedAt().equals(audit.createdAt())) {
            throw new IllegalArgumentException("Audit must match rejection snapshot");
        }
    }

    private Instant now() { return clock.instant().truncatedTo(ChronoUnit.MICROS); }
    private Instant transitionTime(Rejection previous) {
        var time = now();
        return time.isBefore(previous.updatedAt()) ? previous.updatedAt() : time;
    }

    private static long bytes(PayloadRevision p) {
        return p.payload().encoded().getBytes(StandardCharsets.UTF_8).length;
    }

    private void requirePayloadCapacity(long bytes) {
        if (bytes > limits.maxPayloadBytes() - payloadBytes) {
            throw new StorageCapacityExceededException("max-payload-bytes");
        }
    }

    private static void validateDuration(Duration value, Duration maximum) {
        if (value.compareTo(Duration.ofMillis(1)) < 0 || value.compareTo(maximum) > 0) {
            throw new IllegalArgumentException("Invalid worker timing");
        }
    }

    private static void validateScope(String scope) {
        if (scope == null || scope.isBlank() || scope.length() > 128) {
            throw new IllegalArgumentException("Invalid application scope");
        }
    }

    private static final class Entry {
        private Rejection rejection;
        private final List<PayloadRevision> revisions = new ArrayList<>();
        private final List<AuditEvent> audit = new ArrayList<>();
        private final List<RecoveryAttempt> attempts = new ArrayList<>();
        private Entry(Rejection rejection) { this.rejection = rejection; }
    }
}
