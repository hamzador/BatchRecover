package io.github.hamzador.batchrecover.core.domain;

import io.github.hamzador.batchrecover.core.exception.InvalidStatusTransitionException;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class DomainValidationTest {
    private static final UUID ID = UUID.randomUUID();
    private static final Instant NOW = Instant.EPOCH;
    private static final RejectionIdentity IDENTITY = new RejectionIdentity("app", 0, "step", "row");
    private static final ReplayPayload PAYLOAD = new ReplayPayload("type", 1, "{}");

    @Test
    void shouldValidateTextAndNonnegativeBoundaries() {
        assertEquals("a", DomainChecks.text("a", "field", 1));
        assertThrows(NullPointerException.class, () -> DomainChecks.text(null, "field", 1));
        assertThrows(IllegalArgumentException.class, () -> DomainChecks.text(" ", "field", 1));
        assertThrows(IllegalArgumentException.class, () -> DomainChecks.text("ab", "field", 1));
        assertThrows(IllegalArgumentException.class, () -> DomainChecks.text("a\0", "field", 2));
        assertDoesNotThrow(() -> DomainChecks.nonNegative(0, "field"));
        assertThrows(IllegalArgumentException.class, () -> DomainChecks.nonNegative(-1, "field"));
    }

    @Test
    void shouldRejectLeadingNullCharacterInIdentityAndPayload() {
        String invalid = String.valueOf((char) 0) + "data";
        assertThrows(IllegalArgumentException.class,
                () -> new RejectionIdentity(invalid, 0, "step", "row"));
        assertThrows(IllegalArgumentException.class,
                () -> new RejectionIdentity("app", 0, "step", invalid));
        assertThrows(IllegalArgumentException.class,
                () -> new ReplayPayload("type", 1, invalid));
    }

    @Test
    void shouldValidateCaptureAttemptBudgetBoundaries() {
        for (int budget : new int[]{1, 100}) {
            assertEquals(budget, request(budget).maxAttempts());
        }
        assertThrows(IllegalArgumentException.class, () -> request(0));
        assertThrows(IllegalArgumentException.class, () -> request(101));
    }

    private CaptureRequest request(int budget) {
        return new CaptureRequest(IDENTITY, 0, 0, "handler", "ERROR", ErrorCategory.UNKNOWN, PAYLOAD, budget);
    }

    @Test
    void shouldValidateAttemptLifecycleAndErrorCode() {
        assertDoesNotThrow(() -> attempt(0, 1, null, AttemptOutcome.RUNNING, null));
        assertDoesNotThrow(() -> attempt(0, 1, NOW, AttemptOutcome.RECOVERED, null));
        assertDoesNotThrow(() -> attempt(1, 2, NOW.plusSeconds(1), AttemptOutcome.DEAD, "FAILED"));
        assertThrows(IllegalArgumentException.class, () -> attempt(-1, 1, null, AttemptOutcome.RUNNING, null));
        assertThrows(IllegalArgumentException.class, () -> attempt(0, 0, null, AttemptOutcome.RUNNING, null));
        assertThrows(IllegalArgumentException.class, () -> attempt(0, 1, NOW, AttemptOutcome.RUNNING, null));
        assertThrows(IllegalArgumentException.class, () -> attempt(0, 1, null, AttemptOutcome.RECOVERED, null));
        assertThrows(IllegalArgumentException.class, () -> attempt(0, 1, NOW.minusNanos(1), AttemptOutcome.DEAD, "FAILED"));
        assertThrows(IllegalArgumentException.class, () -> attempt(0, 1, NOW, AttemptOutcome.DEAD, " "));
    }

    private RecoveryAttempt attempt(int revision, int number, Instant ended, AttemptOutcome outcome, String code) {
        return new RecoveryAttempt(ID, ID, revision, number, ID, NOW, ended, outcome, code);
    }

    @Test
    void shouldValidateCaptureAndMutationAudits() {
        assertDoesNotThrow(() -> audit(0, AuditAction.CAPTURED, null, RejectionStatus.PENDING));
        assertThrows(IllegalArgumentException.class, () -> audit(0, AuditAction.CAPTURED, RejectionStatus.PENDING, RejectionStatus.PENDING));
        assertThrows(IllegalArgumentException.class, () -> audit(0, AuditAction.CAPTURED, null, RejectionStatus.QUEUED));
        assertThrows(IllegalArgumentException.class, () -> audit(1, AuditAction.CAPTURED, null, RejectionStatus.PENDING));
        assertThrows(IllegalArgumentException.class, () -> audit(0, AuditAction.QUEUED, RejectionStatus.PENDING, RejectionStatus.QUEUED));
        assertThrows(NullPointerException.class, () -> audit(1, AuditAction.QUEUED, null, RejectionStatus.QUEUED));
        assertThrows(InvalidStatusTransitionException.class, () -> audit(1, AuditAction.QUEUED, RejectionStatus.DEAD, RejectionStatus.QUEUED));
        assertDoesNotThrow(() -> audit(1, AuditAction.QUEUED, RejectionStatus.PENDING, RejectionStatus.QUEUED));
    }

    private AuditEvent audit(long version, AuditAction action, RejectionStatus previous, RejectionStatus next) {
        return new AuditEvent(ID, ID, version, action, "actor", "reason", previous, next, NOW);
    }

    @Test
    void shouldRejectInvalidUtf8AndEnforceExactPayloadByteLimit() {
        assertEquals(65536, new ReplayPayload("type", 1, "é".repeat(32768)).encoded().getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
        assertThrows(IllegalArgumentException.class, () -> new ReplayPayload("type", 1, "é".repeat(32769)));
        assertThrows(IllegalArgumentException.class, () -> new ReplayPayload("type", 1, String.valueOf((char) 0xd800)));
        assertThrows(IllegalArgumentException.class, () -> new ReplayPayload("type", 0, "{}"));
        assertEquals("ReplayPayload[typeId=type, schemaVersion=1, encoded=<redacted>]", PAYLOAD.toString());
    }
    @Test
    void shouldValidateRevisionAndIdentityBoundaries() {
        var revision = new PayloadRevision(ID, 0, PAYLOAD, "a".repeat(128), "r".repeat(512), NOW);
        assertEquals(0, revision.revision());
        assertEquals(PAYLOAD, revision.payload());
        assertThrows(IllegalArgumentException.class, () -> new PayloadRevision(ID, -1, PAYLOAD, "actor", "reason", NOW));
        assertThrows(IllegalArgumentException.class, () -> new PayloadRevision(ID, 0, PAYLOAD, "a".repeat(129), "reason", NOW));
        assertThrows(IllegalArgumentException.class, () -> new PayloadRevision(ID, 0, PAYLOAD, "actor", "r".repeat(513), NOW));
        assertThrows(IllegalArgumentException.class, () -> new CaptureRequest(IDENTITY, -1, 0, "handler", "ERROR", ErrorCategory.UNKNOWN, PAYLOAD, 1));
        assertThrows(IllegalArgumentException.class, () -> new CaptureRequest(IDENTITY, 0, -1, "handler", "ERROR", ErrorCategory.UNKNOWN, PAYLOAD, 1));
    }
}
