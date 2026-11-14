package io.github.hamzador.batchrecover.core.service;

import io.github.hamzador.batchrecover.core.domain.*;
import io.github.hamzador.batchrecover.core.exception.HandlerExecutionException;
import io.github.hamzador.batchrecover.core.port.RecoveryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RecoveryEngineTest {
    @Mock private RecoveryRepository repository;
    @Mock private RecoveryHandlerRegistry registry;
    @Spy private RecoverySettings settings = new RecoverySettings("app", Duration.ofSeconds(30),
            new RetryPolicy(Duration.ofMillis(100), Duration.ofSeconds(1)));
    @InjectMocks private RecoveryEngine engine;
    @Captor private ArgumentCaptor<Duration> delayCaptor;
    @Captor private ArgumentCaptor<Consumer<RecoveryWork>> callbackCaptor;
    private RecoveryClaim claim;

    @BeforeEach
    void setUp() {
        claim = new RecoveryClaim(UUID.randomUUID(), UUID.randomUUID(), "app", 1, 2);
    }

    @Test
    void shouldReturnNoWorkWhenQueueIsEmpty() {
        when(repository.claimNext("app", Duration.ofSeconds(30))).thenReturn(Optional.empty());
        assertEquals(RecoveryResult.NO_WORK, engine.runOnce());
        verify(repository).reapExpired(eq("app"), any());
        verify(repository, never()).execute(any(), any());
    }

    @Test
    void shouldExecuteClaimThroughRepositoryCallback() {
        when(repository.claimNext("app", Duration.ofSeconds(30))).thenReturn(Optional.of(claim));
        when(repository.execute(eq(claim), any())).thenReturn(true);
        assertEquals(RecoveryResult.RECOVERED, engine.runOnce());
        verify(repository).execute(eq(claim), callbackCaptor.capture());
        var work = mock(RecoveryWork.class);
        callbackCaptor.getValue().accept(work);
        verify(registry).execute(work);
    }

    @Test
    void shouldScheduleBoundedJitterAfterHandlerFailure() {
        when(repository.claimNext("app", Duration.ofSeconds(30))).thenReturn(Optional.of(claim));
        when(repository.execute(eq(claim), any())).thenThrow(new HandlerExecutionException(RecoveryFailure.HANDLER_FAILED));
        when(repository.fail(eq(claim), eq(RecoveryFailure.HANDLER_FAILED), any()))
                .thenReturn(Optional.of(RejectionStatus.QUEUED));
        assertEquals(RecoveryResult.RETRY_SCHEDULED, engine.runOnce());
        verify(repository).fail(eq(claim), eq(RecoveryFailure.HANDLER_FAILED), delayCaptor.capture());
        assertTrue(delayCaptor.getValue().toMillis() >= 100 && delayCaptor.getValue().toMillis() <= 200);
    }

    @Test
    void shouldReturnCorrectionRequiredAfterValidationFailure() {
        when(repository.claimNext("app", Duration.ofSeconds(30))).thenReturn(Optional.of(claim));
        when(repository.execute(eq(claim), any())).thenThrow(new HandlerExecutionException(RecoveryFailure.VALIDATION_FAILED));
        when(repository.fail(eq(claim), eq(RecoveryFailure.VALIDATION_FAILED), any()))
                .thenReturn(Optional.of(RejectionStatus.PENDING));
        assertEquals(RecoveryResult.CORRECTION_REQUIRED, engine.runOnce());
    }

    @Test
    void shouldNotRecordInfrastructureFailureAsBusinessFailure() {
        when(repository.claimNext("app", Duration.ofSeconds(30))).thenReturn(Optional.of(claim));
        when(repository.execute(eq(claim), any())).thenThrow(new IllegalStateException("commit uncertain"));
        assertThrows(IllegalStateException.class, engine::runOnce);
        verify(repository, never()).fail(any(), any(), any());
    }

    @Test
    void shouldReturnStaleWhenExecutionCannotAcquireClaim() {
        when(repository.claimNext("app", Duration.ofSeconds(30))).thenReturn(Optional.of(claim));
        when(repository.execute(eq(claim), any())).thenReturn(false);
        assertEquals(RecoveryResult.STALE_CLAIM, engine.runOnce());
        verify(repository, never()).fail(any(), any(), any());
    }
}
