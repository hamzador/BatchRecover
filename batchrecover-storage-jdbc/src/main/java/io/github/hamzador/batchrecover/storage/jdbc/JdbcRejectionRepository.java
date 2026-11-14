package io.github.hamzador.batchrecover.storage.jdbc;

import io.github.hamzador.batchrecover.core.domain.*;
import io.github.hamzador.batchrecover.core.exception.CaptureConflictException;
import io.github.hamzador.batchrecover.core.exception.OptimisticLockException;
import io.github.hamzador.batchrecover.core.exception.CorrectionRequiredException;
import io.github.hamzador.batchrecover.core.port.RecoveryStorage;
import io.github.hamzador.batchrecover.core.port.StorageCapabilities;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.time.Duration;
import java.util.function.Consumer;
import java.util.function.IntFunction;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * PostgreSQL adapter. REQUIRED joins an existing JDBC transaction on the same DataSource.
 * Duplicate capture uses READ COMMITTED semantics; callers must retry serialization failures.
 */
public final class JdbcRejectionRepository implements RecoveryStorage {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final TransactionTemplate mandatoryTransactions;
    private final TransactionTemplate workerTransactions;

    public JdbcRejectionRepository(DataSource dataSource, JdbcTransactionManager transactionManager) {
        Objects.requireNonNull(dataSource, "dataSource");
        Objects.requireNonNull(transactionManager, "transactionManager");
        if (transactionManager.getDataSource() != dataSource) {
            throw new IllegalArgumentException("Use the same DataSource and JDBC transaction manager");
        }
        this.jdbc = new JdbcTemplate(dataSource);
        this.transactions = new TransactionTemplate(transactionManager);
        this.mandatoryTransactions = new TransactionTemplate(transactionManager);
        this.mandatoryTransactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_MANDATORY);
        this.workerTransactions = new TransactionTemplate(transactionManager);
        this.workerTransactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.workerTransactions.setTimeout(30);
    }

    @Override
    public StorageCapabilities capabilities() {
        return StorageCapabilities.JDBC;
    }

    @Override
    public Rejection captureInExistingTransaction(Rejection rejection, PayloadRevision original, AuditEvent audit) {
        return Objects.requireNonNull(mandatoryTransactions.execute(
                tx -> capture(rejection, original, audit)));
    }

    @Override
    public Rejection capture(Rejection r, PayloadRevision original, AuditEvent audit) {
        if (r.status() != RejectionStatus.PENDING || r.version() != 0 || r.currentRevision() != 0
                || r.attemptCount() != 0 || original.revision() != 0
                || !original.rejectionId().equals(r.id())
                || !original.payload().typeId().equals(r.payloadType())
                || audit.action() != AuditAction.CAPTURED) {
            throw new IllegalArgumentException("Invalid initial capture");
        }
        validateAudit(r, audit);
        return Objects.requireNonNull(transactions.execute(tx -> {
            RejectionIdentity i = r.identity();
            int count = jdbc.update("""
                INSERT INTO br_rejection (id, application_scope, job_instance_id, job_execution_id,
                    step_execution_id, step_name, source_occurrence_id, phase, handler_id, error_code,
                    category, payload_type, status, current_revision, attempt_count, max_attempts,
                    next_attempt_at, lease_token, lease_expires_at, version, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT ON CONSTRAINT br_rejection_identity DO NOTHING
                """, r.id(), i.applicationScope(), i.jobInstanceId(), r.jobExecutionId(),
                    r.stepExecutionId(), i.stepName(), i.sourceOccurrenceId(), i.phase().name(),
                    r.handlerId(), r.errorCode(), r.category().name(), r.payloadType(), r.status().name(),
                    r.currentRevision(), r.attemptCount(), r.maxAttempts(), time(r.nextAttemptAt()),
                    r.leaseToken(), time(r.leaseExpiresAt()), r.version(), time(r.createdAt()), time(r.updatedAt()));
            if (count == 1) {
                insertRevision(original);
                insertAudit(audit);
                return r;
            }
            Rejection existing = jdbc.queryForObject("""
                SELECT * FROM br_rejection WHERE application_scope = ? AND job_instance_id = ?
                AND step_name = ? AND source_occurrence_id = ? AND phase = ?
                """, REJECTION, i.applicationScope(), i.jobInstanceId(), i.stepName(),
                    i.sourceOccurrenceId(), i.phase().name());
            Objects.requireNonNull(existing, "existing rejection");
            PayloadRevision stored = jdbc.queryForObject("""
                SELECT * FROM br_payload_revision WHERE rejection_id = ? AND revision = 0
                """, REVISION, existing.id());
            if (stored == null || !stored.payload().equals(original.payload())
                    || !existing.handlerId().equals(r.handlerId())) {
                throw new CaptureConflictException(existing.id());
            }
            return existing;
        }));
    }

    @Override
    public Optional<Rejection> findById(UUID id) {
        return jdbc.query("SELECT * FROM br_rejection WHERE id = ?", REJECTION, id)
                .stream().findFirst();
    }

    @Override
    public List<PayloadRevision> findRevisions(UUID id) {
        return jdbc.query("SELECT * FROM br_payload_revision WHERE rejection_id = ? ORDER BY revision",
                REVISION, id);
    }

    @Override
    public List<AuditEvent> findAuditEvents(UUID id) {
        return jdbc.query("SELECT * FROM br_audit_event WHERE rejection_id = ? ORDER BY version",
                AUDIT, id);
    }

    @Override
    public Rejection save(Rejection updated, long expectedVersion,
                          Optional<PayloadRevision> revision, AuditEvent audit) {
        validateAudit(updated, audit);
        if (expectedVersion < 0 || updated.version() != Math.incrementExact(expectedVersion)) {
            throw new IllegalArgumentException("Invalid version increment");
        }
        boolean correction = audit.action() == AuditAction.CORRECTED;
        boolean queue = audit.action() == AuditAction.QUEUED;
        boolean discard = audit.action() == AuditAction.DISCARDED;
        if (!(correction || queue || discard) || correction != revision.isPresent()) {
            throw new IllegalArgumentException("Unsupported mutation");
        }
        if (correction && (audit.previousStatus() != RejectionStatus.PENDING
                || updated.status() != RejectionStatus.PENDING)
                || queue && (audit.previousStatus() != RejectionStatus.PENDING
                || updated.status() != RejectionStatus.QUEUED)
                || discard && (updated.status() != RejectionStatus.DISCARDED
                || (audit.previousStatus() != RejectionStatus.PENDING
                && audit.previousStatus() != RejectionStatus.QUEUED))) {
            throw new IllegalArgumentException("Invalid mutation status");
        }
        revision.ifPresent(p -> {
            if (!p.rejectionId().equals(updated.id()) || p.revision() != updated.currentRevision()
                    || !p.payload().typeId().equals(updated.payloadType())) {
                throw new IllegalArgumentException("Invalid payload revision");
            }
        });
        return Objects.requireNonNull(transactions.execute(tx -> {
            if (queue && Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM br_rejection r JOIN br_recovery_attempt a ON a.rejection_id = r.id
                    WHERE r.id = ? AND r.version = ? AND r.current_revision = a.revision
                    AND a.outcome = 'VALIDATION_FAILURE'
                )
                """, Boolean.class, updated.id(), expectedVersion))) {
                throw new CorrectionRequiredException(updated.id());
            }
            int count = jdbc.update("""
                UPDATE br_rejection SET status = ?, current_revision = ?, next_attempt_at = ?,
                    version = ?, updated_at = ?
                WHERE id = ? AND version = ? AND status = ? AND current_revision = ?
                    AND payload_type = ?
                """, updated.status().name(), updated.currentRevision(), time(updated.nextAttemptAt()),
                    updated.version(), time(updated.updatedAt()), updated.id(), expectedVersion,
                    audit.previousStatus().name(), updated.currentRevision() - (correction ? 1 : 0),
                    updated.payloadType());
            if (count != 1) {
                throw new OptimisticLockException(updated.id());
            }
            revision.ifPresent(this::insertRevision);
            insertAudit(audit);
            return findById(updated.id()).orElseThrow();
        }));
    }

    @Override
    public Optional<RecoveryClaim> claimNext(String scope, Duration leaseDuration) {
        requireWorkerBoundary();
        validateScope(scope);
        if (leaseDuration.compareTo(Duration.ofMillis(1)) < 0
                || leaseDuration.compareTo(Duration.ofDays(1)) > 0) {
            throw new IllegalArgumentException("Invalid lease duration");
        }
        return Objects.requireNonNull(workerTransactions.execute(tx -> {
            var eligible = jdbc.query("""
                SELECT * FROM br_rejection WHERE application_scope = ? AND status = 'QUEUED'
                AND next_attempt_at <= clock_timestamp() AND attempt_count < max_attempts
                ORDER BY next_attempt_at, id LIMIT 1 FOR UPDATE SKIP LOCKED
                """, REJECTION, scope);
            if (eligible.isEmpty()) return Optional.empty();
            Rejection current = eligible.getFirst();
            Instant now = transitionTime(current);
            Rejection claimed = current.claim(UUID.randomUUID(), now.plusMillis(leaseDuration.toMillis()), now);
            updateWorkerState(current, claimed);
            jdbc.update("""
                INSERT INTO br_recovery_attempt (id, rejection_id, revision, attempt_number,
                    lease_token, started_at, outcome) VALUES (?, ?, ?, ?, ?, ?, 'RUNNING')
                """, UUID.randomUUID(), claimed.id(), claimed.currentRevision(),
                    claimed.attemptCount(), claimed.leaseToken(), time(now));
            insertWorkerAudit(current, claimed, AuditAction.CLAIMED, "CLAIMED");
            return Optional.of(RecoveryClaim.from(claimed));
        }));
    }

    @Override
    public boolean execute(RecoveryClaim claim, Consumer<RecoveryWork> callback) {
        requireWorkerBoundary();
        Objects.requireNonNull(callback);
        return Boolean.TRUE.equals(workerTransactions.execute(tx -> {
            var locked = lockClaim(claim);
            if (locked.isEmpty()) return false;
            Rejection current = locked.orElseThrow();
            if (!current.leaseExpiresAt().isAfter(databaseNow())) return false;
            var revision = jdbc.queryForObject("""
                SELECT * FROM br_payload_revision WHERE rejection_id = ? AND revision = ?
                """, REVISION, current.id(), current.currentRevision());
            Objects.requireNonNull(revision, "Pinned payload revision");
            // Bound database statements; handler must also bound CPU and any blocking work itself.
            jdbc.execute("SET LOCAL statement_timeout = '30s'");
            jdbc.execute("SET LOCAL lock_timeout = '5s'");
            callback.accept(new RecoveryWork(current, revision.payload()));
            if (tx.isRollbackOnly()) throw new IllegalStateException("Recovery transaction marked rollback-only");
            var completed = current.recovered(transitionTime(current));
            updateWorkerState(current, completed);
            finishAttempt(current, completed.updatedAt(), AttemptOutcome.RECOVERED, null);
            insertWorkerAudit(current, completed, AuditAction.RECOVERED, "RECOVERED");
            return true;
        }));
    }

    @Override
    public Optional<RejectionStatus> fail(RecoveryClaim claim, RecoveryFailure failure, Duration delay) {
        requireWorkerBoundary();
        if (failure == RecoveryFailure.LEASE_EXPIRED) {
            throw new IllegalArgumentException("Use the lease reaper for expired claims");
        }
        validateDelay(delay);
        return Objects.requireNonNull(workerTransactions.execute(tx ->
                lockClaim(claim).map(current -> finishFailure(current, failure, delay))));
    }

    @Override
    public boolean reapExpired(String scope, IntFunction<Duration> delayForAttempt) {
        requireWorkerBoundary();
        validateScope(scope);
        Objects.requireNonNull(delayForAttempt);
        return Boolean.TRUE.equals(workerTransactions.execute(tx -> {
            var expired = jdbc.query("""
                SELECT * FROM br_rejection WHERE application_scope = ? AND status = 'RUNNING'
                AND lease_expires_at <= clock_timestamp()
                ORDER BY lease_expires_at, id LIMIT 1 FOR UPDATE SKIP LOCKED
                """, REJECTION, scope);
            if (expired.isEmpty()) return false;
            var current = expired.getFirst();
            var delay = delayForAttempt.apply(current.attemptCount());
            validateDelay(delay);
            finishFailure(current, RecoveryFailure.LEASE_EXPIRED, delay);
            return true;
        }));
    }

    @Override
    public List<RecoveryAttempt> findAttempts(UUID id) {
        return jdbc.query("SELECT * FROM br_recovery_attempt WHERE rejection_id = ? ORDER BY attempt_number",
                ATTEMPT, id);
    }

    private Optional<Rejection> lockClaim(RecoveryClaim claim) {
        return jdbc.query("""
                SELECT * FROM br_rejection WHERE id = ? AND application_scope = ?
                AND status = 'RUNNING' AND lease_token = ? FOR UPDATE SKIP LOCKED
                """, REJECTION, claim.rejectionId(), claim.applicationScope(), claim.leaseToken())
                .stream().findFirst().filter(claim::matches);
    }

    private RejectionStatus finishFailure(Rejection current, RecoveryFailure failure, Duration delay) {
        var updated = current.failed(failure, delay, transitionTime(current));
        var status = updated.status();
        AttemptOutcome outcome = status == RejectionStatus.DEAD ? AttemptOutcome.DEAD
                : failure == RecoveryFailure.LEASE_EXPIRED ? AttemptOutcome.EXPIRED
                : failure == RecoveryFailure.VALIDATION_FAILED ? AttemptOutcome.VALIDATION_FAILURE
                : AttemptOutcome.RETRYABLE_FAILURE;
        AuditAction action = status == RejectionStatus.DEAD ? AuditAction.DEAD
                : status == RejectionStatus.PENDING ? AuditAction.VALIDATION_FAILED : AuditAction.RETRY_SCHEDULED;
        updateWorkerState(current, updated);
        finishAttempt(current, updated.updatedAt(), outcome, failure.name());
        insertWorkerAudit(current, updated, action, failure.name());
        return status;
    }

    private void updateWorkerState(Rejection previous, Rejection next) {
        int count = jdbc.update("""
            UPDATE br_rejection SET status=?, attempt_count=?, next_attempt_at=?, lease_token=?,
                lease_expires_at=?, version=?, updated_at=?
            WHERE id=? AND version=? AND status=? AND lease_token IS NOT DISTINCT FROM CAST(? AS uuid)
            """, next.status().name(), next.attemptCount(), time(next.nextAttemptAt()), next.leaseToken(),
                time(next.leaseExpiresAt()), next.version(), time(next.updatedAt()), previous.id(),
                previous.version(), previous.status().name(), previous.leaseToken());
        if (count != 1) throw new OptimisticLockException(previous.id());
    }

    private void finishAttempt(Rejection current, Instant now, AttemptOutcome outcome, String code) {
        int count = jdbc.update("""
            UPDATE br_recovery_attempt SET finished_at=?, outcome=?, error_code=?
            WHERE rejection_id=? AND attempt_number=? AND lease_token=? AND outcome='RUNNING'
            """, time(now), outcome.name(), code, current.id(), current.attemptCount(), current.leaseToken());
        if (count != 1) throw new IllegalStateException("Attempt was not active");
    }

    private void insertWorkerAudit(Rejection previous, Rejection next, AuditAction action, String reason) {
        insertAudit(new AuditEvent(UUID.randomUUID(), next.id(), next.version(), action,
                "recovery-engine", reason, previous.status(), next.status(), next.updatedAt()));
    }

    private Instant databaseNow() {
        return Objects.requireNonNull(jdbc.queryForObject("SELECT clock_timestamp()", OffsetDateTime.class)).toInstant();
    }

    private Instant transitionTime(Rejection current) {
        Instant now = databaseNow();
        return now.isBefore(current.updatedAt()) ? current.updatedAt() : now;
    }

    private static void validateScope(String scope) {
        if (scope == null || scope.isBlank() || scope.length() > 128) {
            throw new IllegalArgumentException("Invalid application scope");
        }
    }

    private static void validateDelay(Duration delay) {
        if (delay.compareTo(Duration.ofMillis(1)) < 0 || delay.compareTo(Duration.ofDays(30)) > 0) {
            throw new IllegalArgumentException("Invalid retry delay");
        }
    }

    private static void requireWorkerBoundary() {
        if (TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("Recovery worker must run outside any enclosing transaction");
        }
    }

    private static final RowMapper<RecoveryAttempt> ATTEMPT = (rs, row) -> new RecoveryAttempt(
            rs.getObject("id", UUID.class), rs.getObject("rejection_id", UUID.class),
            rs.getInt("revision"), rs.getInt("attempt_number"), rs.getObject("lease_token", UUID.class),
            instant(rs, "started_at"), instant(rs, "finished_at"),
            AttemptOutcome.valueOf(rs.getString("outcome")), rs.getString("error_code"));

    private void insertRevision(PayloadRevision p) {
        jdbc.update("""
            INSERT INTO br_payload_revision (rejection_id, revision, payload_type, schema_version,
                encoded_payload, actor, reason, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """, p.rejectionId(), p.revision(), p.payload().typeId(), p.payload().schemaVersion(),
                p.payload().encoded(), p.actor(), p.reason(), time(p.createdAt()));
    }

    private void insertAudit(AuditEvent e) {
        jdbc.update("""
            INSERT INTO br_audit_event (id, rejection_id, version, action, actor, reason,
                previous_status, new_status, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """, e.id(), e.rejectionId(), e.version(), e.action().name(), e.actor(), e.reason(),
                e.previousStatus() == null ? null : e.previousStatus().name(),
                e.newStatus().name(), time(e.createdAt()));
    }

    private static void validateAudit(Rejection r, AuditEvent e) {
        if (!r.id().equals(e.rejectionId()) || r.version() != e.version()
                || r.status() != e.newStatus() || !r.updatedAt().equals(e.createdAt())) {
            throw new IllegalArgumentException("Audit must match rejection snapshot");
        }
    }

    private static OffsetDateTime time(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }

    private static Instant instant(ResultSet rs, String name) throws SQLException {
        OffsetDateTime value = rs.getObject(name, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static final RowMapper<Rejection> REJECTION = (rs, row) -> new Rejection(
            rs.getObject("id", UUID.class),
            new RejectionIdentity(rs.getString("application_scope"), rs.getLong("job_instance_id"),
                    rs.getString("step_name"), rs.getString("source_occurrence_id"),
                    RejectionPhase.valueOf(rs.getString("phase"))),
            rs.getLong("job_execution_id"), rs.getLong("step_execution_id"),
            rs.getString("handler_id"), rs.getString("error_code"), ErrorCategory.valueOf(rs.getString("category")),
            rs.getString("payload_type"), RejectionStatus.valueOf(rs.getString("status")),
            rs.getInt("current_revision"), rs.getInt("attempt_count"), rs.getInt("max_attempts"),
            instant(rs, "next_attempt_at"), rs.getObject("lease_token", UUID.class),
            instant(rs, "lease_expires_at"), rs.getLong("version"),
            instant(rs, "created_at"), instant(rs, "updated_at"));

    private static final RowMapper<PayloadRevision> REVISION = (rs, row) -> new PayloadRevision(
            rs.getObject("rejection_id", UUID.class), rs.getInt("revision"),
            new ReplayPayload(rs.getString("payload_type"), rs.getInt("schema_version"), rs.getString("encoded_payload")),
            rs.getString("actor"), rs.getString("reason"), instant(rs, "created_at"));

    private static final RowMapper<AuditEvent> AUDIT = (rs, row) -> new AuditEvent(
            rs.getObject("id", UUID.class), rs.getObject("rejection_id", UUID.class),
            rs.getLong("version"), AuditAction.valueOf(rs.getString("action")),
            rs.getString("actor"), rs.getString("reason"),
            rs.getString("previous_status") == null ? null : RejectionStatus.valueOf(rs.getString("previous_status")),
            RejectionStatus.valueOf(rs.getString("new_status")), instant(rs, "created_at"));
}
