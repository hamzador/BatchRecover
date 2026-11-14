package io.github.hamzador.batchrecover.storage.memory;

import io.github.hamzador.batchrecover.core.domain.*;
import io.github.hamzador.batchrecover.core.service.RejectionService;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class InMemoryRecoveryStorageTest {
    private final MutableClock clock = new MutableClock();
    private InMemoryRecoveryStorage storage;
    private RejectionService service;

    private void initialize(MemoryStorageLimits limits) {
        storage = new InMemoryRecoveryStorage(clock, limits);
        service = new RejectionService(storage, clock);
    }

    private CaptureRequest request(String row, String payload) {
        return new CaptureRequest(new RejectionIdentity("app", 1, "step", row), 2, 3,
                "handler", "INVALID", ErrorCategory.BUSINESS, new ReplayPayload("customer", 1, payload), 2);
    }

    @Test
    void shouldRejectNewCaptureAtCapacityWithoutEviction() {
        initialize(new MemoryStorageLimits(1, 2, 100));
        var captured = service.capture(request("row:1", "invalid"));
        var discarded = service.discard(captured.id(), 0, "operator", "discard");
        assertEquals(discarded, service.capture(request("row:1", "invalid")));
        assertThrows(StorageCapacityExceededException.class, () -> service.capture(request("row:2", "invalid")));
        assertEquals(discarded, service.get(captured.id()));
        assertEquals(2, storage.findAuditEvents(captured.id()).size());
    }

    @Test
    void shouldBoundRevisionHistoryWithoutPartialMutation() {
        initialize(new MemoryStorageLimits(2, 1, 100));
        var r = service.capture(request("row:1", "invalid"));
        assertThrows(StorageCapacityExceededException.class,
                () -> service.correct(r.id(), 0, new ReplayPayload("customer", 1, "fixed"), "operator", "fix"));
        assertEquals(r, service.get(r.id()));
        assertEquals(1, storage.findRevisions(r.id()).size());
        assertEquals(1, storage.findAuditEvents(r.id()).size());
    }

    @Test
    void shouldCountUtf8BytesAcrossAllRevisions() {
        initialize(new MemoryStorageLimits(2, 3, 5));
        var r = service.capture(request("row:1", "é"));
        var fixed = service.correct(r.id(), 0, new ReplayPayload("customer", 1, "abc"), "operator", "fix");
        assertThrows(StorageCapacityExceededException.class,
                () -> service.capture(request("row:2", "x")));
        assertThrows(StorageCapacityExceededException.class,
                () -> service.correct(r.id(), 1, new ReplayPayload("customer", 1, "x"), "operator", "fix"));
        assertEquals(fixed, service.get(r.id()));
        assertEquals(2, storage.findRevisions(r.id()).size());
    }

    @Test
    void shouldExpireClaimRejectOldTokenAndHonorDelay() {
        initialize(MemoryStorageLimits.DEFAULT);
        var r = service.capture(request("row:1", "invalid"));
        service.queue(r.id(), 0, "operator", "retry");
        var claim = storage.claimNext("app", Duration.ofSeconds(1)).orElseThrow();
        clock.advance(Duration.ofSeconds(1));
        assertFalse(storage.execute(claim, work -> fail("expired")));
        assertTrue(storage.reapExpired("app", n -> Duration.ofSeconds(2)));
        assertEquals(AttemptOutcome.EXPIRED, storage.findAttempts(r.id()).getFirst().outcome());
        assertTrue(storage.claimNext("app", Duration.ofSeconds(1)).isEmpty());
        clock.advance(Duration.ofSeconds(2));
        var next = storage.claimNext("app", Duration.ofSeconds(1)).orElseThrow();
        assertFalse(storage.execute(claim, work -> fail("stale")));
        clock.advance(Duration.ofSeconds(1));
        assertTrue(storage.reapExpired("app", n -> Duration.ofSeconds(1)));
        assertEquals(RejectionStatus.DEAD, service.get(r.id()).status());
        assertEquals(AttemptOutcome.DEAD, storage.findAttempts(r.id()).getLast().outcome());
        assertFalse(storage.execute(next, work -> fail("expired")));
    }

    @Test
    void shouldNotReapAnExecutingClaimEvenAfterExpiry() {
        initialize(MemoryStorageLimits.DEFAULT);
        var r = service.capture(request("row:1", "invalid"));
        service.queue(r.id(), 0, "operator", "replay");
        var claim = storage.claimNext("app", Duration.ofSeconds(1)).orElseThrow();
        assertTrue(storage.execute(claim, work -> {
            clock.advance(Duration.ofSeconds(2));
            assertFalse(storage.reapExpired("app", n -> Duration.ofSeconds(1)));
            assertTrue(storage.fail(claim, RecoveryFailure.HANDLER_FAILED, Duration.ofSeconds(1)).isEmpty());
        }));
        assertEquals(RejectionStatus.RECOVERED, service.get(r.id()).status());
    }

    @Test
    void shouldReleaseExecutionFenceAfterHandlerThrowsButNotRollbackExternalEffects() {
        initialize(MemoryStorageLimits.DEFAULT);
        var r = service.capture(request("row:1", "invalid"));
        service.queue(r.id(), 0, "operator", "replay");
        var claim = storage.claimNext("app", Duration.ofSeconds(1)).orElseThrow();
        var effects = new java.util.ArrayList<String>();
        assertThrows(IllegalStateException.class, () -> storage.execute(claim, work -> {
            effects.add("external");
            throw new IllegalStateException("failure");
        }));
        assertEquals(1, effects.size());
        assertEquals(RejectionStatus.QUEUED,
                storage.fail(claim, RecoveryFailure.HANDLER_FAILED, Duration.ofSeconds(1)).orElseThrow());
        assertEquals(AttemptOutcome.RETRYABLE_FAILURE, storage.findAttempts(r.id()).getFirst().outcome());
    }

    @Test
    void shouldRejectInvalidLimits() {
        assertThrows(IllegalArgumentException.class, () -> new MemoryStorageLimits(0, 2, 100));
        assertThrows(IllegalArgumentException.class, () -> new MemoryStorageLimits(1, 101, 100));
        assertThrows(IllegalArgumentException.class, () -> new MemoryStorageLimits(1, 2, 0));
    }

    @Test
    void shouldNotPersistRecordsAcrossStorageInstances() {
        initialize(MemoryStorageLimits.DEFAULT);
        var r = service.capture(request("row:1", "invalid"));
        var fresh = new InMemoryRecoveryStorage(clock, MemoryStorageLimits.DEFAULT);
        assertTrue(fresh.findById(r.id()).isEmpty());
    }

    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> time = new AtomicReference<>(Instant.parse("2026-01-01T00:00:00Z"));
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { throw new UnsupportedOperationException(); }
        @Override public Instant instant() { return time.get(); }
        void advance(Duration duration) { time.updateAndGet(t -> t.plus(duration)); }
    }
}
