package io.github.hamzador.batchrecover.storage.jdbc;

import io.github.hamzador.batchrecover.core.domain.*;
import io.github.hamzador.batchrecover.core.exception.CaptureConflictException;
import io.github.hamzador.batchrecover.core.exception.OptimisticLockException;
import io.github.hamzador.batchrecover.core.port.RejectionRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.JdbcTransactionManager;
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
public final class JdbcRejectionRepository implements RejectionRepository {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public JdbcRejectionRepository(DataSource dataSource, JdbcTransactionManager transactionManager) {
        Objects.requireNonNull(dataSource, "dataSource");
        Objects.requireNonNull(transactionManager, "transactionManager");
        if (transactionManager.getDataSource() != dataSource) {
            throw new IllegalArgumentException("Use the same DataSource and JDBC transaction manager");
        }
        this.jdbc = new JdbcTemplate(dataSource);
        this.transactions = new TransactionTemplate(transactionManager);
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
