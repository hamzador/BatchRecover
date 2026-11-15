package io.github.hamzador.batchrecover.core.service;

import io.github.hamzador.batchrecover.core.domain.*;
import io.github.hamzador.batchrecover.core.exception.*;
import io.github.hamzador.batchrecover.core.port.RejectionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RejectionServiceTest {
    @Mock private RejectionRepository repository;
    @Mock private Clock clock;
    @InjectMocks private RejectionService service;
    @Captor private ArgumentCaptor<Rejection> rejectionCaptor;
    @Captor private ArgumentCaptor<PayloadRevision> revisionCaptor;
    @Captor private ArgumentCaptor<AuditEvent> auditCaptor;

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private CaptureRequest request;
    private Rejection initial;

    @BeforeEach
    void setUp() {
        request = new CaptureRequest(new RejectionIdentity("app", 1, "import", "file:1"),
                2, 3, "customer", "INVALID", ErrorCategory.BUSINESS,
                new ReplayPayload("customer", 1, "{}"), 3);
        initial = Rejection.capture(UUID.randomUUID(), request, NOW);
    }

    @Test
    void shouldCapturePendingRejectionWithOriginalRevisionAndAudit() {
        when(clock.instant()).thenReturn(NOW);
        when(repository.capture(any(), any(), any())).thenAnswer(invocation -> invocation.getArgument(0));

        Rejection result = service.capture(request);

        verify(repository).capture(rejectionCaptor.capture(), revisionCaptor.capture(), auditCaptor.capture());
        assertEquals(result, rejectionCaptor.getValue());
        assertEquals(RejectionStatus.PENDING, result.status());
        assertEquals(result.id(), revisionCaptor.getValue().rejectionId());
        assertEquals(0, revisionCaptor.getValue().revision());
        assertEquals(request.payload(), revisionCaptor.getValue().payload());
        assertEquals(AuditAction.CAPTURED, auditCaptor.getValue().action());
        assertNull(auditCaptor.getValue().previousStatus());
    }

    @Test
    void shouldAppendCorrectionWithIncrementedVersion() {
        when(clock.instant()).thenReturn(NOW);
        when(repository.findById(initial.id())).thenReturn(Optional.of(initial));
        when(repository.save(any(), eq(0L), any(), any())).thenAnswer(invocation -> invocation.getArgument(0));
        var payload = new ReplayPayload("customer", 2, "{\"valid\":true}");

        Rejection updated = service.correct(initial.id(), 0, payload, "operator", "fix");

        assertEquals(1, updated.version());
        assertEquals(1, updated.currentRevision());
        assertEquals(RejectionStatus.PENDING, updated.status());
        verify(repository).save(eq(updated), eq(0L),
                eq(Optional.of(new PayloadRevision(initial.id(), 1, payload, "operator", "fix", NOW))),
                auditCaptor.capture());
        assertEquals(AuditAction.CORRECTED, auditCaptor.getValue().action());
    }

    @Test
    void shouldRejectStaleVersionBeforeWriting() {
        when(repository.findById(initial.id())).thenReturn(Optional.of(initial));
        assertThrows(OptimisticLockException.class,
                () -> service.queue(initial.id(), 1, "operator", "replay"));
        verify(repository, never()).save(any(), anyLong(), any(), any());
    }

    @Test
    void shouldRejectCorrectionWhileQueued() {
        when(repository.findById(initial.id())).thenReturn(Optional.of(initial.queue(NOW)));
        when(clock.instant()).thenReturn(NOW);
        assertThrows(InvalidStatusTransitionException.class,
                () -> service.correct(initial.id(), 1, request.payload(), "operator", "fix"));
        verify(repository, never()).save(any(), anyLong(), any(), any());
    }

    @Test
    void shouldRejectPayloadTypeChange() {
        when(repository.findById(initial.id())).thenReturn(Optional.of(initial));
        assertThrows(IllegalArgumentException.class,
                () -> service.correct(initial.id(), 0, new ReplayPayload("other", 1, "{}"), "operator", "fix"));
        verify(repository, never()).save(any(), anyLong(), any(), any());
    }

    @Test
    void shouldReportMissingRejection() {
        when(repository.findById(initial.id())).thenReturn(Optional.empty());
        assertThrows(RejectionNotFoundException.class, () -> service.get(initial.id()));
    }
    @Test
    void shouldRequireExistingTransactionAndPreserveCaptureAudit() {
        when(clock.instant()).thenReturn(NOW.plusNanos(999));
        when(repository.captureInExistingTransaction(any(), any(), any())).thenAnswer(i -> i.getArgument(0));
        var result = service.captureInExistingTransaction(request);
        verify(repository).captureInExistingTransaction(eq(result), revisionCaptor.capture(), auditCaptor.capture());
        assertEquals(NOW, result.createdAt());
        assertEquals(NOW, revisionCaptor.getValue().createdAt());
        assertEquals(NOW, auditCaptor.getValue().createdAt());
        verify(repository, never()).capture(any(), any(), any());
    }

    @Test
    void shouldClampMutationTimeWhenHostClockMovesBack() {
        when(clock.instant()).thenReturn(NOW.minusSeconds(1));
        when(repository.findById(initial.id())).thenReturn(Optional.of(initial));
        when(repository.save(any(), eq(0L), any(), any())).thenAnswer(i -> i.getArgument(0));
        var queued = service.queue(initial.id(), 0, "operator", "queue");
        assertEquals(NOW, queued.updatedAt());
        assertEquals(NOW, queued.nextAttemptAt());
        verify(repository).save(eq(queued), eq(0L), eq(Optional.empty()), auditCaptor.capture());
        assertEquals(AuditAction.QUEUED, auditCaptor.getValue().action());
        assertEquals(RejectionStatus.PENDING, auditCaptor.getValue().previousStatus());
        assertEquals(RejectionStatus.QUEUED, auditCaptor.getValue().newStatus());
    }

    @Test
    void shouldAuditDiscardWithoutNewPayloadRevision() {
        when(clock.instant()).thenReturn(NOW.plusSeconds(1));
        when(repository.findById(initial.id())).thenReturn(Optional.of(initial));
        when(repository.save(any(), eq(0L), any(), any())).thenAnswer(i -> i.getArgument(0));
        var discarded = service.discard(initial.id(), 0, "operator", "discard");
        assertEquals(RejectionStatus.DISCARDED, discarded.status());
        assertEquals(NOW.plusSeconds(1), discarded.updatedAt());
        verify(repository).save(eq(discarded), eq(0L), eq(Optional.empty()), auditCaptor.capture());
        assertEquals(AuditAction.DISCARDED, auditCaptor.getValue().action());
        assertEquals("operator", auditCaptor.getValue().actor());
        assertEquals("discard", auditCaptor.getValue().reason());
    }
}
