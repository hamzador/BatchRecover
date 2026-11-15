package io.github.hamzador.batchrecover.core.contract;

import io.github.hamzador.batchrecover.core.domain.*;
import io.github.hamzador.batchrecover.core.exception.*;
import io.github.hamzador.batchrecover.core.port.RecoveryStorage;
import io.github.hamzador.batchrecover.core.service.RejectionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** Backend-independent invariants. Concrete subclasses provide fresh storage and a clock. */
public abstract class RecoveryStorageContract {
    protected RecoveryStorage storage;
    protected RejectionService service;
    protected abstract RecoveryStorage createStorage();
    protected Clock clock() { return Clock.systemUTC(); }
    protected static final Duration LEASE = Duration.ofSeconds(30);
    protected static final Duration DELAY = Duration.ofMillis(10);

    @BeforeEach
    protected void setUpContract() {
        storage = createStorage();
        service = new RejectionService(storage, clock());
    }

    protected CaptureRequest request(String scope, String occurrence, String payload, int attempts) {
        return new CaptureRequest(new RejectionIdentity(scope, 1, "import", occurrence), 2, 3,
                "handler", "INVALID", ErrorCategory.BUSINESS, new ReplayPayload("customer", 1, payload), attempts);
    }

    protected Rejection queued(String occurrence, int attempts) {
        var r = service.capture(request("app", occurrence, "invalid", attempts));
        return service.queue(r.id(), r.version(), "operator", "replay");
    }

    @Test
    protected void shouldDeduplicateAndPreserveOriginalPayload() {
        var request = request("app", "row:1", "invalid", 3);
        var r = service.capture(request);
        var updated = service.correct(r.id(), 0, new ReplayPayload("customer", 1, "fixed"), "operator", "fix");
        assertEquals(updated, service.capture(request));
        assertEquals("invalid", storage.findRevisions(r.id()).getFirst().payload().encoded());
        assertEquals("fixed", storage.findRevisions(r.id()).getLast().payload().encoded());
        assertEquals(List.of(AuditAction.CAPTURED, AuditAction.CORRECTED),
                storage.findAuditEvents(r.id()).stream().map(AuditEvent::action).toList());
        assertThrows(CaptureConflictException.class,
                () -> service.capture(request("app", "row:1", "different", 3)));
    }

    @Test
    protected void shouldRejectStaleVersionWithoutMutatingHistory() {
        var r = service.capture(request("app", "row:1", "invalid", 3));
        service.queue(r.id(), 0, "operator", "replay");
        assertThrows(OptimisticLockException.class, () -> service.discard(r.id(), 0, "operator", "stale"));
        assertEquals(RejectionStatus.QUEUED, service.get(r.id()).status());
        assertEquals(2, storage.findAuditEvents(r.id()).size());
    }

    @Test
    protected void shouldListOnlyPendingRejectionsWithinScopeAndLimit() {
        var first = service.capture(request("app", "row:1", "invalid", 3));
        var second = service.capture(request("app", "row:2", "invalid", 3));
        service.capture(request("foreign", "row:1", "invalid", 3));
        queued("row:3", 3);
        assertEquals(1, storage.findPending("app", 1).size());
        assertEquals(java.util.Set.of(first.id(), second.id()),
                storage.findPending("app", 10).stream().map(Rejection::id).collect(java.util.stream.Collectors.toSet()));
        assertThrows(IllegalArgumentException.class, () -> storage.findPending("app", 0));
    }

    @Test
    protected void shouldReplayPinnedRevisionAndFinalizeOnlyOnce() {
        var r = service.capture(request("app", "row:1", "invalid", 3));
        var fixed = service.correct(r.id(), 0, new ReplayPayload("customer", 2, "fixed"), "operator", "fix");
        service.queue(r.id(), fixed.version(), "operator", "replay");
        var claim = storage.claimNext("app", LEASE).orElseThrow();
        assertTrue(storage.execute(claim, work -> {
            assertEquals("fixed", work.payload().encoded());
            assertEquals(2, work.payload().schemaVersion());
            assertEquals(r.id(), work.context().idempotencyKey());
            assertEquals(1, work.context().revision());
        }));
        assertFalse(storage.execute(claim, work -> fail("stale callback")));
        assertTrue(storage.fail(claim, RecoveryFailure.HANDLER_FAILED, DELAY).isEmpty());
        assertEquals(RejectionStatus.RECOVERED, service.get(r.id()).status());
        assertEquals(AttemptOutcome.RECOVERED, storage.findAttempts(r.id()).getFirst().outcome());
        assertEquals(AuditAction.RECOVERED, storage.findAuditEvents(r.id()).getLast().action());
    }

    @Test
    protected void shouldRequireNewRevisionAfterValidationFailure() {
        var r = queued("row:1", 2);
        var claim = storage.claimNext("app", LEASE).orElseThrow();
        assertEquals(RejectionStatus.PENDING, storage.fail(claim, RecoveryFailure.VALIDATION_FAILED, DELAY).orElseThrow());
        var pending = service.get(r.id());
        assertThrows(CorrectionRequiredException.class, () -> service.queue(r.id(), pending.version(), "operator", "retry"));
        var fixed = service.correct(r.id(), pending.version(), new ReplayPayload("customer", 1, "fixed"), "operator", "fix");
        service.queue(r.id(), fixed.version(), "operator", "replay");
        var fresh = storage.claimNext("app", LEASE).orElseThrow();
        assertNotEquals(claim.leaseToken(), fresh.leaseToken());
        assertFalse(storage.execute(claim, work -> fail("stale callback")));
        assertEquals(RejectionStatus.DEAD, storage.fail(fresh, RecoveryFailure.HANDLER_FAILED, DELAY).orElseThrow());
        assertEquals(2, storage.findAttempts(r.id()).size());
        assertTrue(storage.claimNext("app", LEASE).isEmpty());
    }

    @Test
    protected void shouldDeduplicateConcurrentCapture() throws Exception {
        var request = request("app", "row:1", "invalid", 3);
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<Rejection> task = () -> {
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("timeout");
                return service.capture(request);
            };
            var one = pool.submit(task);
            var two = pool.submit(task);
            start.countDown();
            assertEquals(one.get(10, TimeUnit.SECONDS).id(), two.get(10, TimeUnit.SECONDS).id());
            assertEquals(1, storage.findPending("app", 10).size());
        }
    }

    @Test
    protected void shouldAllowOnlyOneConcurrentClaimForOneRecord() throws Exception {
        var r = queued("row:1", 3);
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<java.util.Optional<RecoveryClaim>> task = () -> {
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("timeout");
                return storage.claimNext("app", LEASE);
            };
            var one = pool.submit(task);
            var two = pool.submit(task);
            start.countDown();
            assertEquals(1, java.util.stream.Stream.of(one.get(10, TimeUnit.SECONDS), two.get(10, TimeUnit.SECONDS))
                    .filter(java.util.Optional::isPresent).count());
        }
        assertEquals(1, storage.findAttempts(r.id()).size());
    }

    @Test
    protected void shouldNeverInvokeSameClaimConcurrently() throws Exception {
        var r = queued("row:1", 3);
        var claim = storage.claimNext("app", LEASE).orElseThrow();
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var calls = new AtomicInteger();
        try (var pool = Executors.newSingleThreadExecutor()) {
            var first = pool.submit(() -> storage.execute(claim, work -> {
                calls.incrementAndGet();
                started.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("timeout");
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(ex);
                }
            }));
            try {
                assertTrue(started.await(10, TimeUnit.SECONDS));
                assertFalse(storage.execute(claim, work -> calls.incrementAndGet()));
            } finally {
                release.countDown();
            }
            assertTrue(first.get(10, TimeUnit.SECONDS));
        }
        assertEquals(1, calls.get());
        assertEquals(RejectionStatus.RECOVERED, service.get(r.id()).status());
    }
    @Test
    @org.junit.jupiter.api.Timeout(180)
    protected void shouldDrainContendedQueueWithoutLosingOrDuplicatingRecords() throws Exception {
        final int count = 80;
        var expected = new java.util.HashSet<java.util.UUID>();
        for (int i = 0; i < count; i++) expected.add(queued("load:" + i, 3).id());
        var calls = new java.util.concurrent.ConcurrentHashMap<java.util.UUID, Integer>();
        var completed = new AtomicInteger();
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(4);
        try {
            var futures = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int worker = 0; worker < 4; worker++) {
                futures.add(pool.submit(() -> {
                    try {
                        if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("start timeout");
                        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120);
                        while (completed.get() < count && System.nanoTime() < deadline) {
                            var claim = storage.claimNext("app", LEASE);
                            if (claim.isEmpty()) {
                                TimeUnit.MILLISECONDS.sleep(5);
                                continue;
                            }
                            boolean executed = storage.execute(claim.orElseThrow(), work -> {
                                assertTrue(expected.contains(work.context().idempotencyKey()));
                                assertEquals(1, calls.merge(work.context().idempotencyKey(), 1, Integer::sum));
                            });
                            assertTrue(executed);
                            completed.incrementAndGet();
                        }
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(ex);
                    }
                }));
            }
            start.countDown();
            for (var future : futures) future.get(150, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        assertEquals(count, completed.get());
        assertEquals(expected, calls.keySet());
        assertTrue(storage.claimNext("app", LEASE).isEmpty());
        for (var id : expected) {
            assertEquals(RejectionStatus.RECOVERED, service.get(id).status());
            assertEquals(1, storage.findAttempts(id).size());
            assertEquals(List.of(AuditAction.CAPTURED, AuditAction.QUEUED, AuditAction.CLAIMED, AuditAction.RECOVERED),
                    storage.findAuditEvents(id).stream().map(AuditEvent::action).toList());
        }
    }

    @Test
    protected void shouldRejectConcurrentCorrectionsWithOneWinningRevision() throws Exception {
        var r = service.capture(request("app", "row:1", "bad", 3));
        var start = new CountDownLatch(1);
        var winners = new AtomicInteger();
        try (var pool = Executors.newFixedThreadPool(2)) {
            var tasks = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (String payload : List.of("one", "two")) {
                tasks.add(pool.submit(() -> {
                    try {
                        if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("timeout");
                        service.correct(r.id(), 0, new ReplayPayload("customer", 1, payload), "operator", "fix");
                        winners.incrementAndGet();
                    } catch (OptimisticLockException expected) {
                        // Exactly one compare-and-set loser.
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(ex);
                    }
                }));
            }
            start.countDown();
            for (var task : tasks) task.get(15, TimeUnit.SECONDS);
        }
        assertEquals(1, winners.get());
        assertEquals(1, service.get(r.id()).version());
        assertEquals(2, storage.findRevisions(r.id()).size());
        assertEquals(2, storage.findAuditEvents(r.id()).size());
    }
    @Test
    protected void shouldRejectInvalidQueryAndWorkerArgumentsBeforeChangingState() {
        var r = queued("row:1", 3);
        for (String scope : java.util.Arrays.asList(null, "", " ", "x".repeat(129))) {
            assertThrows(IllegalArgumentException.class, () -> storage.findPending(scope, 1));
            assertThrows(IllegalArgumentException.class, () -> storage.claimNext(scope, LEASE));
            assertThrows(IllegalArgumentException.class, () -> storage.reapExpired(scope, n -> DELAY));
        }
        assertThrows(IllegalArgumentException.class, () -> storage.findPending("app", 1001));
        assertThrows(IllegalArgumentException.class, () -> storage.claimNext("app", Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> storage.claimNext("app", Duration.ofDays(1).plusMillis(1)));
        assertTrue(storage.claimNext("foreign", LEASE).isEmpty());
        assertEquals(RejectionStatus.QUEUED, service.get(r.id()).status());
        var claim = storage.claimNext("app", LEASE).orElseThrow();
        assertThrows(IllegalArgumentException.class, () -> storage.fail(claim, RecoveryFailure.LEASE_EXPIRED, DELAY));
        assertThrows(IllegalArgumentException.class, () -> storage.fail(claim, RecoveryFailure.HANDLER_FAILED, Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> storage.fail(claim, RecoveryFailure.HANDLER_FAILED, Duration.ofDays(31)));
        assertFalse(storage.reapExpired("app", n -> DELAY));
        assertEquals(RejectionStatus.RUNNING, service.get(r.id()).status());
        assertEquals(AttemptOutcome.RUNNING, storage.findAttempts(r.id()).getFirst().outcome());
    }

    @Test
    protected void shouldReturnEmptyHistoryForUnknownIdAndRejectForgedClaim() {
        var missing = java.util.UUID.randomUUID();
        assertTrue(storage.findById(missing).isEmpty());
        assertTrue(storage.findRevisions(missing).isEmpty());
        assertTrue(storage.findAuditEvents(missing).isEmpty());
        assertTrue(storage.findAttempts(missing).isEmpty());
        var fake = new RecoveryClaim(missing, java.util.UUID.randomUUID(), "app", 0, 1);
        assertFalse(storage.execute(fake, work -> fail("Missing claim executed")));
        assertTrue(storage.fail(fake, RecoveryFailure.HANDLER_FAILED, DELAY).isEmpty());
        var r = queued("row:1", 3);
        var claim = storage.claimNext("app", LEASE).orElseThrow();
        var wrongRevision = new RecoveryClaim(r.id(), claim.leaseToken(), "app", 1, claim.attemptNumber());
        assertFalse(storage.execute(wrongRevision, work -> fail("Wrong revision executed")));
        assertTrue(storage.fail(wrongRevision, RecoveryFailure.HANDLER_FAILED, DELAY).isEmpty());
        assertEquals(RejectionStatus.RUNNING, service.get(r.id()).status());
        assertTrue(storage.execute(claim, work -> {}));
    }

    @Test
    protected void shouldRejectConflictingHandlerAndPreserveOriginalCapture() {
        var req = request("app", "row:1", "bad", 3);
        var r = service.capture(req);
        var changed = new CaptureRequest(req.identity(), req.jobExecutionId(), req.stepExecutionId(),
                "other-handler", req.errorCode(), req.category(), req.payload(), req.maxAttempts());
        assertThrows(CaptureConflictException.class, () -> service.capture(changed));
        assertEquals(r, service.get(r.id()));
        assertEquals(1, storage.findRevisions(r.id()).size());
        assertEquals(1, storage.findAuditEvents(r.id()).size());
    }

    @Test
    protected void shouldRejectMismatchedCaptureRevisionAndAuditWithoutWriting() {
        var req = request("app", "row:1", "bad", 3);
        java.time.Instant now = clock().instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        var r = Rejection.capture(java.util.UUID.randomUUID(), req, now);
        var id = r.id();
        var valid = new PayloadRevision(id, 0, req.payload(), "system", "capture", now);
        var audit = new AuditEvent(java.util.UUID.randomUUID(), id, 0, AuditAction.CAPTURED,
                "system", "capture", null, RejectionStatus.PENDING, now);
        for (var wrong : List.of(
                new PayloadRevision(id, 1, req.payload(), "system", "capture", now),
                new PayloadRevision(java.util.UUID.randomUUID(), 0, req.payload(), "system", "capture", now),
                new PayloadRevision(id, 0, new ReplayPayload("other", 1, "bad"), "system", "capture", now))) {
            assertThrows(IllegalArgumentException.class, () -> storage.capture(r, wrong, audit));
        }
        var foreignAudit = new AuditEvent(java.util.UUID.randomUUID(), java.util.UUID.randomUUID(), 0, AuditAction.CAPTURED,
                "system", "capture", null, RejectionStatus.PENDING, now);
        var wrongTime = new AuditEvent(java.util.UUID.randomUUID(), id, 0, AuditAction.CAPTURED,
                "system", "capture", null, RejectionStatus.PENDING, now.plusSeconds(1));
        assertThrows(IllegalArgumentException.class, () -> storage.capture(r, valid, foreignAudit));
        assertThrows(IllegalArgumentException.class, () -> storage.capture(r, valid, wrongTime));
        assertTrue(storage.findPending("app", 1).isEmpty());
        assertTrue(storage.findRevisions(id).isEmpty());
        assertTrue(storage.findAuditEvents(id).isEmpty());
    }

    @Test
    protected void shouldRejectInvalidCorrectionPayloadWithoutChangingSnapshot() {
        var r = service.capture(request("app", "row:1", "bad", 3));
        var updated = r.correct(r.updatedAt());
        var audit = new AuditEvent(java.util.UUID.randomUUID(), r.id(), 1, AuditAction.CORRECTED,
                "operator", "fix", RejectionStatus.PENDING, RejectionStatus.PENDING, r.updatedAt());
        for (var invalid : List.of(
                new PayloadRevision(java.util.UUID.randomUUID(), 1, new ReplayPayload("customer", 1, "fixed"), "operator", "fix", r.updatedAt()),
                new PayloadRevision(r.id(), 0, new ReplayPayload("customer", 1, "fixed"), "operator", "fix", r.updatedAt()),
                new PayloadRevision(r.id(), 1, new ReplayPayload("other", 1, "fixed"), "operator", "fix", r.updatedAt()))) {
            assertThrows(IllegalArgumentException.class, () -> storage.save(updated, 0, java.util.Optional.of(invalid), audit));
        }
        assertThrows(IllegalArgumentException.class, () -> storage.save(updated, 0, java.util.Optional.empty(), audit));
        assertEquals(r, service.get(r.id()));
        assertEquals(1, storage.findRevisions(r.id()).size());
        assertEquals(1, storage.findAuditEvents(r.id()).size());
    }
}
