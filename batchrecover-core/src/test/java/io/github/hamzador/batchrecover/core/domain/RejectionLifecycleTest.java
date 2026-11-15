package io.github.hamzador.batchrecover.core.domain;

import io.github.hamzador.batchrecover.core.exception.InvalidStatusTransitionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class RejectionLifecycleTest {
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID TOKEN = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private Rejection pending;

    @BeforeEach
    void setUp() {
        pending = capture(2);
    }

    private Rejection capture(int budget) {
        return Rejection.capture(ID, new CaptureRequest(new RejectionIdentity("app", 11, "step", "row:17"),
                12, 13, "handler", "INVALID", ErrorCategory.BUSINESS,
                new ReplayPayload("customer", 1, "bad"), budget), NOW);
    }

    private Rejection running() {
        return pending.queue(NOW).claim(TOKEN, NOW.plusSeconds(10), NOW);
    }

    @Test
    void shouldCaptureEveryIdentityAndProvenanceField() {
        assertEquals(ID, pending.id());
        assertEquals(new RejectionIdentity("app", 11, "step", "row:17"), pending.identity());
        assertEquals(12, pending.jobExecutionId());
        assertEquals(13, pending.stepExecutionId());
        assertEquals("handler", pending.handlerId());
        assertEquals("INVALID", pending.errorCode());
        assertEquals(ErrorCategory.BUSINESS, pending.category());
        assertEquals("customer", pending.payloadType());
        assertEquals(RejectionStatus.PENDING, pending.status());
        assertEquals(0, pending.currentRevision());
        assertEquals(0, pending.attemptCount());
        assertEquals(2, pending.maxAttempts());
        assertEquals(0, pending.version());
        assertEquals(NOW, pending.createdAt());
        assertEquals(NOW, pending.updatedAt());
        assertNull(pending.leaseToken());
        assertNull(pending.leaseExpiresAt());
        assertNull(pending.nextAttemptAt());
    }

    @Test
    void shouldPreserveProvenanceAcrossCorrectionClaimAndRecovery() {
        var corrected = pending.correct(NOW.plusSeconds(1));
        var queued = corrected.queue(NOW.plusSeconds(2));
        var claim = queued.claim(TOKEN, NOW.plusSeconds(20), NOW.plusSeconds(2));
        var recovered = claim.recovered(NOW.plusSeconds(3));
        assertEquals(1, corrected.version());
        assertEquals(1, corrected.currentRevision());
        assertEquals(NOW.plusSeconds(2), queued.nextAttemptAt());
        assertEquals(TOKEN, claim.leaseToken());
        assertEquals(NOW.plusSeconds(20), claim.leaseExpiresAt());
        assertEquals(1, claim.attemptCount());
        assertEquals(3, claim.version());
        assertEquals(RejectionStatus.RECOVERED, recovered.status());
        assertEquals(4, recovered.version());
        assertEquals(1, recovered.currentRevision());
        assertEquals(1, recovered.attemptCount());
        assertEquals(pending.identity(), recovered.identity());
        assertEquals(pending.id(), recovered.id());
        assertEquals(pending.jobExecutionId(), recovered.jobExecutionId());
        assertEquals(pending.stepExecutionId(), recovered.stepExecutionId());
        assertEquals(pending.handlerId(), recovered.handlerId());
        assertEquals(pending.errorCode(), recovered.errorCode());
        assertEquals(pending.payloadType(), recovered.payloadType());
        assertEquals(pending.category(), recovered.category());
        assertEquals(pending.maxAttempts(), recovered.maxAttempts());
        assertEquals(NOW, recovered.createdAt());
        assertEquals(NOW.plusSeconds(3), recovered.updatedAt());
        assertNull(recovered.leaseToken());
        assertNull(recovered.leaseExpiresAt());
        assertNull(recovered.nextAttemptAt());
    }

    @ParameterizedTest
    @EnumSource(RecoveryFailure.class)
    void shouldClassifyFailuresAndExhaustBudget(RecoveryFailure failure) {
        var r = running();
        var failed = r.failed(failure, Duration.ofSeconds(2), NOW.plusSeconds(1));
        var expected = failure == RecoveryFailure.VALIDATION_FAILED
                ? RejectionStatus.PENDING : RejectionStatus.QUEUED;
        assertEquals(expected, failed.status());
        assertEquals(3, failed.version());
        assertEquals(1, failed.attemptCount());
        assertNull(failed.leaseToken());
        assertNull(failed.leaseExpiresAt());
        assertEquals(expected == RejectionStatus.QUEUED ? NOW.plusSeconds(3) : null, failed.nextAttemptAt());
        var last = capture(1).queue(NOW).claim(TOKEN, NOW.plusSeconds(1), NOW)
                .failed(failure, Duration.ofSeconds(1), NOW);
        assertEquals(RejectionStatus.DEAD, last.status());
        assertNull(last.nextAttemptAt());
        assertEquals(1, last.attemptCount());
    }

    @Test
    void shouldRejectIneligibleClaimsAndWrongSourceStates() {
        var queued = pending.queue(NOW.plusSeconds(2));
        assertThrows(IllegalStateException.class, () -> queued.claim(TOKEN, NOW.plusSeconds(10), NOW));
        assertThrows(IllegalStateException.class, () -> queued.claim(TOKEN, NOW.plusSeconds(2), NOW.plusSeconds(2)));
        assertThrows(IllegalStateException.class, () -> queued.claim(TOKEN, NOW.plusSeconds(1), NOW.plusSeconds(2)));
        assertThrows(InvalidStatusTransitionException.class, () -> pending.claim(TOKEN, NOW.plusSeconds(1), NOW));
        assertThrows(InvalidStatusTransitionException.class, () -> running().correct(NOW));
        assertThrows(InvalidStatusTransitionException.class, () -> queued.queue(NOW));
        assertThrows(InvalidStatusTransitionException.class, () -> pending.recovered(NOW));
        assertThrows(InvalidStatusTransitionException.class, () -> pending.failed(RecoveryFailure.VALIDATION_FAILED, Duration.ofSeconds(1), NOW));
        assertThrows(IllegalArgumentException.class, () -> running().failed(RecoveryFailure.HANDLER_FAILED, Duration.ZERO, NOW));
        assertThrows(IllegalArgumentException.class, () -> running().failed(RecoveryFailure.HANDLER_FAILED, Duration.ofNanos(-1), NOW));
        assertThrows(NullPointerException.class, () -> running().failed(null, Duration.ofSeconds(1), NOW));
    }

    @Test
    void shouldDiscardOnlyPendingOrQueuedAndClearSchedule() {
        for (var r : java.util.List.of(pending, pending.queue(NOW))) {
            var discarded = r.discard(NOW.plusSeconds(1));
            assertEquals(RejectionStatus.DISCARDED, discarded.status());
            assertEquals(r.version() + 1, discarded.version());
            assertNull(discarded.nextAttemptAt());
            assertThrows(InvalidStatusTransitionException.class, () -> discarded.queue(NOW));
        }
        assertThrows(InvalidStatusTransitionException.class, () -> running().discard(NOW));
    }

    @Test
    void shouldRejectInvalidCountersAndTimestampsIndividually() {
        assertThrows(IllegalArgumentException.class, () -> snapshot(-1, 0, 2, 0, NOW, RejectionStatus.PENDING, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> snapshot(0, 0, 2, -1, NOW, RejectionStatus.PENDING, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> snapshot(0, 0, 0, 0, NOW, RejectionStatus.PENDING, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> snapshot(0, 0, 101, 0, NOW, RejectionStatus.PENDING, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> snapshot(0, -1, 2, 0, NOW, RejectionStatus.PENDING, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> snapshot(0, 3, 2, 0, NOW, RejectionStatus.DEAD, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> snapshot(0, 0, 2, 0, NOW.minusNanos(1), RejectionStatus.PENDING, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> snapshot(0, 2, 2, 0, NOW, RejectionStatus.PENDING, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> snapshot(0, 2, 2, 0, NOW, RejectionStatus.QUEUED, NOW, null, null));
        assertDoesNotThrow(() -> snapshot(0, 100, 100, 0, NOW, RejectionStatus.DEAD, null, null, null));
    }

    @Test
    void shouldRejectInconsistentScheduleAndLeaseFields() {
        assertThrows(IllegalArgumentException.class, () -> snapshot(0, 0, 2, 0, NOW, RejectionStatus.QUEUED, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> snapshot(0, 0, 2, 0, NOW, RejectionStatus.PENDING, NOW, null, null));
        assertThrows(IllegalArgumentException.class, () -> snapshot(0, 1, 2, 0, NOW, RejectionStatus.RUNNING, null, null, NOW));
        assertThrows(IllegalArgumentException.class, () -> snapshot(0, 1, 2, 0, NOW, RejectionStatus.RUNNING, null, TOKEN, null));
        assertThrows(IllegalArgumentException.class, () -> snapshot(0, 0, 2, 0, NOW, RejectionStatus.PENDING, null, TOKEN, null));
        assertThrows(IllegalArgumentException.class, () -> snapshot(0, 0, 2, 0, NOW, RejectionStatus.PENDING, null, null, NOW));
        assertDoesNotThrow(() -> snapshot(0, 1, 2, 0, NOW, RejectionStatus.RUNNING, null, TOKEN, NOW.plusSeconds(1)));
    }

    @Test
    void shouldFenceEveryClaimCoordinate() {
        var r = running();
        var claim = RecoveryClaim.from(r);
        assertTrue(claim.matches(r));
        assertFalse(claim.matches(pending));
        assertFalse(new RecoveryClaim(UUID.randomUUID(), TOKEN, "app", 0, 1).matches(r));
        assertFalse(new RecoveryClaim(ID, UUID.randomUUID(), "app", 0, 1).matches(r));
        assertFalse(new RecoveryClaim(ID, TOKEN, "other", 0, 1).matches(r));
        assertFalse(new RecoveryClaim(ID, TOKEN, "app", 1, 1).matches(r));
        assertFalse(new RecoveryClaim(ID, TOKEN, "app", 0, 2).matches(r));
        assertThrows(IllegalArgumentException.class, () -> RecoveryClaim.from(pending));
        assertThrows(IllegalArgumentException.class, () -> new RecoveryClaim(ID, TOKEN, "app", -1, 1));
        assertThrows(IllegalArgumentException.class, () -> new RecoveryClaim(ID, TOKEN, "app", 0, 0));
        assertEquals(new RecoveryContext(ID, ID, pending.identity(), 0, 1), RecoveryContext.from(r));
    }

    private Rejection snapshot(int revision, int attempts, int max, long version, Instant updated,
                               RejectionStatus status, Instant due, UUID token, Instant expiration) {
        return new Rejection(ID, pending.identity(), 12, 13, "handler", "INVALID", ErrorCategory.BUSINESS,
                "customer", status, revision, attempts, max, due, token, expiration, version, NOW, updated);
    }
}
