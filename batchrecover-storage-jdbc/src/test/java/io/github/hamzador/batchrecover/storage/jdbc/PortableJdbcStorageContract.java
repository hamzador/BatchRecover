package io.github.hamzador.batchrecover.storage.jdbc;

import io.github.hamzador.batchrecover.core.contract.RecoveryStorageContract;
import io.github.hamzador.batchrecover.core.domain.*;
import io.github.hamzador.batchrecover.core.port.RecoveryStorage;
import io.github.hamzador.batchrecover.core.exception.HandlerExecutionException;
import io.github.hamzador.batchrecover.storage.jdbc.dialect.StandardSqlDialect;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class PortableJdbcStorageContract extends RecoveryStorageContract {
    private DataSource source;
    private JdbcTransactionManager manager;
    private JdbcTemplate jdbc;
    protected abstract DataSource dataSource();
    protected abstract StandardSqlDialect expectedDialect();

    @org.junit.jupiter.api.BeforeAll
    void initializeSchema() {
        source = dataSource();
        manager = new JdbcTransactionManager(source);
        jdbc = new JdbcTemplate(source);
        var detected = new JdbcRejectionRepository(source, manager);
        assertSame(expectedDialect(), detected.dialect());
        for (String script : detected.dialect().migrationScripts()) {
            new ResourceDatabasePopulator(new ByteArrayResource(script.getBytes(StandardCharsets.UTF_8))).execute(source);
        }
        jdbc.execute("CREATE TABLE dialect_effect (id VARCHAR(36) PRIMARY KEY, payload VARCHAR(100) NOT NULL)"
                + (expectedDialect() == StandardSqlDialect.MYSQL ? " ENGINE=InnoDB" : ""));
    }

    @Override protected RecoveryStorage createStorage() {
        for (String table : java.util.List.of("br_recovery_attempt", "br_audit_event", "br_payload_revision", "br_rejection", "dialect_effect")) {
            jdbc.update("DELETE FROM " + table);
        }
        return new JdbcRejectionRepository(source, manager);
    }

    @Test
    void shouldRollbackMandatoryCaptureWithChunkAndRejectNoTransaction() {
        var request = request("app", "row:1", "invalid", 3);
        assertThrows(org.springframework.transaction.IllegalTransactionStateException.class,
                () -> service.captureInExistingTransaction(request));
        new TransactionTemplate(manager).executeWithoutResult(tx -> {
            service.captureInExistingTransaction(request);
            assertEquals(1, storage.findPending("app", 1).size());
            tx.setRollbackOnly();
        });
        assertTrue(storage.findPending("app", 1).isEmpty());
        assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM br_payload_revision", Long.class));
        assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM br_audit_event", Long.class));
        new TransactionTemplate(manager).executeWithoutResult(tx -> service.captureInExistingTransaction(request));
        assertEquals(1, storage.findPending("app", 1).size());
    }

    @Test
    void shouldRollbackHandlerEffectAndCommitOnlySuccessfulReplay() {
        var r = queued("row:1", 3);
        var first = storage.claimNext("app", LEASE).orElseThrow();
        assertThrows(HandlerExecutionException.class, () -> storage.execute(first, work -> {
            jdbc.update("INSERT INTO dialect_effect(id,payload) VALUES (?,?)",
                    work.context().idempotencyKey().toString(), "failed");
            throw new HandlerExecutionException(RecoveryFailure.HANDLER_FAILED);
        }));
        assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM dialect_effect", Long.class));
        assertEquals(RejectionStatus.RUNNING, service.get(r.id()).status());
        storage.fail(first, RecoveryFailure.HANDLER_FAILED, DELAY).orElseThrow();
        jdbc.update("UPDATE br_rejection SET next_attempt_at=? WHERE id=?",
                expectedDialect().bindTime(Instant.EPOCH), expectedDialect().bindUuid(r.id()));
        var second = storage.claimNext("app", LEASE).orElseThrow();
        assertTrue(storage.execute(second, work -> jdbc.update("INSERT INTO dialect_effect(id,payload) VALUES (?,?)",
                work.context().idempotencyKey().toString(), "committed")));
        assertEquals(1L, jdbc.queryForObject("SELECT count(*) FROM dialect_effect", Long.class));
        assertEquals(RejectionStatus.RECOVERED, service.get(r.id()).status());
        assertFalse(storage.execute(first, work -> fail("Stale claim executed")));
        assertEquals(2, storage.findAttempts(r.id()).size());
    }

    @Test
    void shouldReapExpiredTokenAndPreserveScopeIsolation() {
        var r = queued("row:1", 2);
        var claim = storage.claimNext("app", LEASE).orElseThrow();
        assertFalse(storage.reapExpired("foreign", n -> DELAY));
        jdbc.update("UPDATE br_rejection SET lease_expires_at=? WHERE id=?",
                expectedDialect().bindTime(Instant.EPOCH), expectedDialect().bindUuid(r.id()));
        assertFalse(storage.execute(claim, work -> fail("Expired claim executed")));
        assertTrue(storage.reapExpired("app", n -> DELAY));
        assertEquals(RejectionStatus.QUEUED, service.get(r.id()).status());
        assertEquals(AttemptOutcome.EXPIRED, storage.findAttempts(r.id()).getFirst().outcome());
        assertTrue(storage.fail(claim, RecoveryFailure.HANDLER_FAILED, DELAY).isEmpty());
    }

    @Test
    void shouldStoreMaximumUtf8PayloadAndDistinguishCaseSensitiveIdentity() {
        String maximum = "é".repeat(32768);
        var one = service.capture(request("app", "row:A", maximum, 3));
        var two = service.capture(request("app", "row:a", "other", 3));
        assertNotEquals(one.id(), two.id());
        assertEquals(maximum, storage.findRevisions(one.id()).getFirst().payload().encoded());
        assertEquals(2, storage.findPending("app", 10).size());
        assertTrue(storage.findPending("APP", 10).isEmpty());
    }

    @Test
    void shouldReadDurableRecordsThroughNewRepositoryInstance() {
        var r = queued("row:1", 3);
        var second = new JdbcRejectionRepository(source, manager);
        assertEquals(r, second.findById(r.id()).orElseThrow());
        assertEquals("invalid", second.findRevisions(r.id()).getFirst().payload().encoded());
        assertTrue(second.claimNext("app", LEASE).isPresent());
    }
}
