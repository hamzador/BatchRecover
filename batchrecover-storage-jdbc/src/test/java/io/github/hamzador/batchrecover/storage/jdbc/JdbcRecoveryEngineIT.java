package io.github.hamzador.batchrecover.storage.jdbc;

import io.github.hamzador.batchrecover.core.domain.*;
import io.github.hamzador.batchrecover.core.exception.RecoveryValidationException;
import io.github.hamzador.batchrecover.core.port.RecoveryHandler;
import io.github.hamzador.batchrecover.core.codec.VersionedPayloadCodec;
import io.github.hamzador.batchrecover.core.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class JdbcRecoveryEngineIT {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");
    private JdbcTemplate jdbc;
    private JdbcRejectionRepository repository;
    private JdbcTransactionManager manager;
    private RejectionService service;
    private static final Duration LEASE = Duration.ofSeconds(30);
    private static final Duration DELAY = Duration.ofMillis(100);

    @BeforeEach
    void setUp() {
        var source = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbc = new JdbcTemplate(source);
        jdbc.execute("DROP TABLE IF EXISTS br_recovery_attempt, br_audit_event, br_payload_revision, br_rejection, demo_effect CASCADE");
        new ResourceDatabasePopulator(
                new ClassPathResource("db/batchrecover/V1__create_batchrecover_tables.sql"),
                new ClassPathResource("db/batchrecover/V2__scope_worker_indexes.sql")).execute(source);
        jdbc.execute("CREATE TABLE demo_effect (operation_id uuid PRIMARY KEY, payload text NOT NULL)");
        manager = new JdbcTransactionManager(source);
        repository = new JdbcRejectionRepository(source, manager);
        service = new RejectionService(repository, Clock.systemUTC());
    }

    @Test
    void shouldCommitBusinessEffectAndRecoveredStateTogether() {
        var r = queued("app", "row:1", 3);
        assertEquals(RecoveryResult.RECOVERED, engine(this::writeEffect).runOnce());
        assertEquals(RejectionStatus.RECOVERED, service.get(r.id()).status());
        assertEquals(1, effectCount());
        assertEquals(RecoveryResult.NO_WORK, engine(this::writeEffect).runOnce());
        assertEquals(1, effectCount());
        var attempt = repository.findAttempts(r.id()).getFirst();
        assertEquals(AttemptOutcome.RECOVERED, attempt.outcome());
        assertNotNull(attempt.finishedAt());
        assertEquals(AuditAction.RECOVERED, repository.findAuditEvents(r.id()).getLast().action());
    }

    @Test
    void shouldRollbackHandlerWriteThenRetryWithSameIdempotencyKey() {
        var r = queued("app", "row:1", 3);
        var failed = engine((item, context) -> {
            writeEffect(item, context);
            throw new IllegalStateException("simulated handler error");
        });
        assertEquals(RecoveryResult.RETRY_SCHEDULED, failed.runOnce());
        assertEquals(0, effectCount());
        assertEquals(RejectionStatus.QUEUED, service.get(r.id()).status());
        assertEquals(AttemptOutcome.RETRYABLE_FAILURE, repository.findAttempts(r.id()).getFirst().outcome());
        makeDue(r.id());
        assertEquals(RecoveryResult.RECOVERED, engine((item, context) -> {
            assertEquals(r.id(), context.idempotencyKey());
            assertEquals(2, context.attemptNumber());
            writeEffect(item, context);
        }).runOnce());
        assertEquals(1, effectCount());
        assertEquals(2, repository.findAttempts(r.id()).size());
    }

    @Test
    void shouldRollbackBusinessEffectWhenSuccessAuditCannotCommit() {
        var r = queued("app", "row:1", 3);
        jdbc.execute("ALTER TABLE br_audit_event ADD CONSTRAINT forbid_success CHECK (action <> 'RECOVERED')");
        assertThrows(org.springframework.dao.DataAccessException.class, () -> engine(this::writeEffect).runOnce());
        assertEquals(0, effectCount());
        assertEquals(RejectionStatus.RUNNING, service.get(r.id()).status());
        assertEquals(AttemptOutcome.RUNNING, repository.findAttempts(r.id()).getFirst().outcome());
        jdbc.execute("ALTER TABLE br_audit_event DROP CONSTRAINT forbid_success");
        expire(r.id());
        assertTrue(engine(this::writeEffect).reapOnce());
        makeDue(r.id());
        assertEquals(RecoveryResult.RECOVERED, engine(this::writeEffect).runOnce());
        assertEquals(1, effectCount());
    }

    @Test
    void shouldPreventConcurrentWorkersFromApplyingSameEffect() throws Exception {
        var r = queued("app", "row:1", 3);
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var executions = new AtomicInteger();
        var engine = engine((item, context) -> {
            executions.incrementAndGet();
            writeEffect(item, context);
            started.countDown();
            try {
                if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("timeout");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        });
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(engine::runOnce);
            try {
                assertTrue(started.await(10, TimeUnit.SECONDS));
                var second = pool.submit(engine::runOnce);
                assertEquals(RecoveryResult.NO_WORK, second.get(5, TimeUnit.SECONDS));
            } finally {
                release.countDown();
            }
            assertEquals(RecoveryResult.RECOVERED, first.get(10, TimeUnit.SECONDS));
        }
        assertEquals(1, executions.get());
        assertEquals(1, effectCount());
        assertEquals(1, repository.findAttempts(r.id()).size());
    }

    @Test
    void shouldClaimDifferentRowsConcurrentlyAndRespectApplicationScope() throws Exception {
        var first = queued("app", "row:1", 3);
        var second = queued("app", "row:2", 3);
        var foreign = queued("other", "row:1", 3);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var one = pool.submit(() -> repository.claimNext("app", LEASE).orElseThrow());
            var two = pool.submit(() -> repository.claimNext("app", LEASE).orElseThrow());
            assertEquals(java.util.Set.of(first.id(), second.id()),
                    java.util.Set.of(one.get(10, TimeUnit.SECONDS).rejectionId(), two.get(10, TimeUnit.SECONDS).rejectionId()));
        }
        assertTrue(repository.claimNext("app", LEASE).isEmpty());
        assertEquals(RejectionStatus.QUEUED, service.get(foreign.id()).status());
    }

    @Test
    void shouldReapExpiredClaimAndRejectStaleTokenAfterReclaim() {
        var r = queued("app", "row:1", 3);
        var stale = repository.claimNext("app", LEASE).orElseThrow();
        expire(r.id());
        assertFalse(repository.execute(stale, work -> fail("Expired claim ran")));
        assertTrue(repository.reapExpired("app", attempt -> DELAY));
        assertEquals(AttemptOutcome.EXPIRED, repository.findAttempts(r.id()).getFirst().outcome());
        makeDue(r.id());
        var fresh = repository.claimNext("app", LEASE).orElseThrow();
        assertNotEquals(stale.leaseToken(), fresh.leaseToken());
        assertFalse(repository.execute(stale, work -> fail("Stale claim ran")));
        assertTrue(repository.fail(stale, RecoveryFailure.HANDLER_FAILED, DELAY).isEmpty());
        assertTrue(repository.execute(fresh, work -> writeEffect(work.payload().encoded(), work.context())));
        assertEquals(1, effectCount());
        assertEquals(2, repository.findAttempts(r.id()).size());
        assertEquals(RejectionStatus.RECOVERED, service.get(r.id()).status());
    }

    @Test
    void shouldNotReapExpiredLeaseWhileExecutionHoldsRowLock() throws Exception {
        var r = queued("app", "row:1", 3);
        var claim = repository.claimNext("app", LEASE).orElseThrow();
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var pool = Executors.newSingleThreadExecutor()) {
            var result = pool.submit(() -> repository.execute(claim, work -> {
                // Simulate expiry during execution under the same row lock.
                expire(r.id());
                started.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("timeout");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
                writeEffect(work.payload().encoded(), work.context());
            }));
            try {
                assertTrue(started.await(10, TimeUnit.SECONDS));
                assertFalse(repository.reapExpired("app", attempt -> DELAY));
            } finally {
                release.countDown();
            }
            assertTrue(result.get(10, TimeUnit.SECONDS));
        }
        assertEquals(RejectionStatus.RECOVERED, service.get(r.id()).status());
    }

    @Test
    void shouldConsumeAttemptBudgetForExpiredClaims() {
        var r = queued("app", "row:1", 1);
        repository.claimNext("app", LEASE).orElseThrow();
        expire(r.id());
        assertTrue(repository.reapExpired("app", attempt -> DELAY));
        assertEquals(RejectionStatus.DEAD, service.get(r.id()).status());
        assertEquals(AttemptOutcome.DEAD, repository.findAttempts(r.id()).getFirst().outcome());
        assertEquals("LEASE_EXPIRED", repository.findAttempts(r.id()).getFirst().errorCode());
        assertTrue(repository.claimNext("app", LEASE).isEmpty());
    }

    @Test
    void shouldStopRetryingAfterBudgetExhausted() {
        var r = queued("app", "row:1", 2);
        var worker = engine((item, context) -> {throw new IllegalStateException("failure");});
        assertEquals(RecoveryResult.RETRY_SCHEDULED, worker.runOnce());
        makeDue(r.id());
        assertEquals(RecoveryResult.DEAD, worker.runOnce());
        assertEquals(RecoveryResult.NO_WORK, worker.runOnce());
        assertEquals(2, service.get(r.id()).attemptCount());
        assertEquals(AttemptOutcome.DEAD, repository.findAttempts(r.id()).getLast().outcome());
    }

    @Test
    void shouldRequireCorrectionAfterBusinessValidationFailure() {
        var r = queued("app", "row:1", 3);
        var worker = engine((item, context) -> { throw new RecoveryValidationException(); });
        assertEquals(RecoveryResult.CORRECTION_REQUIRED, worker.runOnce());
        var pending = service.get(r.id());
        assertEquals(RejectionStatus.PENDING, pending.status());
        var corrected = service.correct(r.id(), pending.version(),
                new ReplayPayload("customer", 1, "fixed"), "operator", "correction");
        service.queue(r.id(), corrected.version(), "operator", "replay");
        assertEquals(RecoveryResult.RECOVERED, engine(this::writeEffect).runOnce());
        assertEquals("fixed", jdbc.queryForObject("SELECT payload FROM demo_effect", String.class));
        assertEquals(1, repository.findAttempts(r.id()).getLast().revision());
        assertEquals(2, repository.findAttempts(r.id()).size());
    }

    @Test
    void shouldRejectWorkersInsideAnOuterTransaction() {
        new TransactionTemplate(manager).executeWithoutResult(tx -> {
            assertThrows(IllegalStateException.class, () -> repository.claimNext("app", LEASE));
        });
    }

    private Rejection queued(String scope, String occurrence, int maxAttempts) {
        var r = service.capture(new CaptureRequest(new RejectionIdentity(scope, 1, "step", occurrence),
                2, 3, "h", "INVALID", ErrorCategory.BUSINESS, new ReplayPayload("customer", 1, "original"), maxAttempts));
        return service.queue(r.id(), r.version(), "operator", "replay");
    }

    private RecoveryEngine engine(BiConsumer<String, RecoveryContext> effect) {
        var codec = new VersionedPayloadCodec<String>("customer", 1, value -> value, Map.of(1, value -> value));
        var handler = new RecoveryHandler<String>() {
            @Override public void validate(String item, RecoveryContext context) {}
            @Override public void replay(String item, RecoveryContext context) { effect.accept(item, context); }
        };
        return new RecoveryEngine(repository,
                new RecoveryHandlerRegistry(List.of(new RecoveryHandlerRegistry.Registration<>("h", codec, handler))),
                new RecoverySettings("app", LEASE, new RetryPolicy(DELAY, Duration.ofSeconds(1))));
    }

    private void writeEffect(String item, RecoveryContext context) {
        jdbc.update("INSERT INTO demo_effect(operation_id,payload) VALUES (?,?)", context.idempotencyKey(), item);
    }
    private int effectCount() {
        return jdbc.queryForObject("SELECT count(*) FROM demo_effect", Integer.class);
    }
    private void expire(UUID id) {
        jdbc.update("UPDATE br_rejection SET lease_expires_at=clock_timestamp()-interval '1 second' WHERE id=?", id);
    }
    private void makeDue(UUID id) {
        jdbc.update("UPDATE br_rejection SET next_attempt_at=clock_timestamp()-interval '1 second' WHERE id=?", id);
    }
}
