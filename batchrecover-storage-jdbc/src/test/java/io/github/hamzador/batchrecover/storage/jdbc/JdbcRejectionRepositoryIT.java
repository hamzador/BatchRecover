package io.github.hamzador.batchrecover.storage.jdbc;

import io.github.hamzador.batchrecover.core.domain.*;
import io.github.hamzador.batchrecover.core.exception.*;
import io.github.hamzador.batchrecover.core.service.RejectionService;
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
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class JdbcRejectionRepositoryIT {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    private JdbcTemplate jdbc;
    private JdbcTransactionManager manager;
    private JdbcRejectionRepository repository;
    private RejectionService service;
    private CaptureRequest request;

    @BeforeEach
    void setUp() {
        var dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("DROP TABLE IF EXISTS br_recovery_attempt, br_audit_event, br_payload_revision, br_rejection CASCADE");
        new ResourceDatabasePopulator(new ClassPathResource("db/batchrecover/V1__create_batchrecover_tables.sql"))
                .execute(dataSource);
        manager = new JdbcTransactionManager(dataSource);
        repository = new JdbcRejectionRepository(dataSource, manager);
        service = new RejectionService(repository, Clock.fixed(
                Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC));
        request = new CaptureRequest(new RejectionIdentity("app", 1, "import", "file:1"),
                2, 3, "customer", "INVALID", ErrorCategory.BUSINESS,
                new ReplayPayload("customer", 1, "{\"valid\":false}"), 3);
    }

    @Test
    void shouldPersistCaptureWithOriginalRevisionAndAudit() {
        Rejection captured = service.capture(request);

        assertEquals(captured, service.get(captured.id()));
        assertEquals(request.payload(), repository.findRevisions(captured.id()).getFirst().payload());
        assertEquals(AuditAction.CAPTURED, repository.findAuditEvents(captured.id()).getFirst().action());
    }

    @Test
    void shouldDeduplicateCaptureAcrossJobExecutionsWithoutOverwritingCorrection() {
        Rejection captured = service.capture(request);
        Rejection corrected = service.correct(captured.id(), 0,
                new ReplayPayload("customer", 1, "{\"valid\":true}"), "operator", "fix");
        var repeated = new CaptureRequest(request.identity(), 20, 30, request.handlerId(),
                request.errorCode(), request.category(), request.payload(), 3);

        assertEquals(corrected, service.capture(repeated));
        assertEquals(2, repository.findRevisions(captured.id()).size());
        assertEquals(2, repository.findAuditEvents(captured.id()).size());
        assertEquals(1, rowCount());
        assertEquals(2, service.get(captured.id()).jobExecutionId());
    }

    @Test
    void shouldRejectConflictingOriginalPayload() {
        Rejection captured = service.capture(request);
        var different = new CaptureRequest(request.identity(), 2, 3, request.handlerId(),
                request.errorCode(), request.category(), new ReplayPayload("customer", 1, "{}"), 3);
        assertThrows(CaptureConflictException.class, () -> service.capture(different));
        assertEquals(1, repository.findRevisions(captured.id()).size());
    }

    @Test
    void shouldRollbackCaptureWithOuterJdbcTransaction() {
        new TransactionTemplate(manager).executeWithoutResult(tx -> {
            Rejection captured = service.capture(request);
            assertTrue(repository.findById(captured.id()).isPresent());
            tx.setRollbackOnly();
        });

        assertEquals(0, rowCount());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM br_payload_revision", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM br_audit_event", Integer.class));
    }

    @Test
    void shouldKeepOriginalRevisionAndRejectStaleMutation() {
        Rejection captured = service.capture(request);
        var corrected = service.correct(captured.id(), 0,
                new ReplayPayload("customer", 2, "{\"valid\":true}"), "operator", "fix");

        assertEquals(1, corrected.version());
        assertEquals(request.payload(), repository.findRevisions(captured.id()).getFirst().payload());
        assertThrows(OptimisticLockException.class,
                () -> service.discard(captured.id(), 0, "operator", "discard"));
        var queued = service.queue(captured.id(), 1, "operator", "replay");
        assertEquals(RejectionStatus.QUEUED, queued.status());
        assertThrows(InvalidStatusTransitionException.class,
                () -> service.correct(captured.id(), 2, request.payload(), "operator", "fix"));
        assertEquals(3, repository.findAuditEvents(captured.id()).size());
        assertEquals(2, repository.findRevisions(captured.id()).size());
    }

    @Test
    void shouldDeduplicateConcurrentCapture() throws Exception {
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<Rejection> task = () -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Start timeout");
                }
                return service.capture(request);
            };
            var first = executor.submit(task);
            var second = executor.submit(task);
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            var a = first.get(15, TimeUnit.SECONDS);
            var b = second.get(15, TimeUnit.SECONDS);
            assertEquals(a.id(), b.id());
            assertEquals(1, rowCount());
            assertEquals(1, repository.findAuditEvents(a.id()).size());
        }
    }

    @Test
    void shouldRollbackMutationWhenAuditInsertFails() {
        var captured = service.capture(request);
        jdbc.execute("ALTER TABLE br_audit_event ADD CONSTRAINT test_forbid_correction CHECK (action <> 'CORRECTED')");
        assertThrows(org.springframework.dao.DataAccessException.class,
                () -> service.correct(captured.id(), 0, new ReplayPayload("customer", 1, "{}"), "operator", "fix"));
        assertEquals(captured, service.get(captured.id()));
        assertEquals(1, repository.findRevisions(captured.id()).size());
        assertEquals(1, repository.findAuditEvents(captured.id()).size());
    }

    @Test
    void shouldRejectLostUpdateAtRepositoryBoundary() {
        var captured = service.capture(request);
        var stale = captured.discard(captured.createdAt());
        service.queue(captured.id(), 0, "operator", "replay");
        var audit = new AuditEvent(java.util.UUID.randomUUID(), captured.id(), 1, AuditAction.DISCARDED,
                "operator", "discard", RejectionStatus.PENDING, RejectionStatus.DISCARDED, stale.updatedAt());

        assertThrows(OptimisticLockException.class,
                () -> repository.save(stale, 0, java.util.Optional.empty(), audit));
        assertEquals(RejectionStatus.QUEUED, service.get(captured.id()).status());
    }

    @Test
    void shouldRejectTransactionalCaptureWithoutExistingTransaction() {
        assertThrows(org.springframework.transaction.IllegalTransactionStateException.class,
                () -> service.captureInExistingTransaction(request));
        assertEquals(0, rowCount());
    }

    @Test
    void shouldJoinExistingTransactionForMandatoryCapture() {
        new TransactionTemplate(manager).executeWithoutResult(tx -> {
            service.captureInExistingTransaction(request);
            assertEquals(1, rowCount());
            tx.setRollbackOnly();
        });
        assertEquals(0, rowCount());
    }

    private int rowCount() {
        return jdbc.queryForObject("SELECT count(*) FROM br_rejection", Integer.class);
    }
}
