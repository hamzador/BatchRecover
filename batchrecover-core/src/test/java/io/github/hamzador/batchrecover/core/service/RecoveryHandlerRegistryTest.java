package io.github.hamzador.batchrecover.core.service;

import io.github.hamzador.batchrecover.core.domain.*;
import io.github.hamzador.batchrecover.core.exception.*;
import io.github.hamzador.batchrecover.core.port.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RecoveryHandlerRegistryTest {
    @Mock private PayloadCodec<String> codec;
    @Mock private RecoveryHandler<String> handler;
    @Captor private ArgumentCaptor<RecoveryContext> contextCaptor;
    private RecoveryHandlerRegistry registry;
    private RecoveryWork work;

    @BeforeEach
    void setUp() {
        registry = new RecoveryHandlerRegistry(List.of(new RecoveryHandlerRegistry.Registration<>("h", codec, handler)));
        var payload = new ReplayPayload("customer", 2, "{}");
        var request = new CaptureRequest(new RejectionIdentity("app", 1, "step", "row:1"),
                2, 3, "h", "INVALID", ErrorCategory.BUSINESS, payload, 3);
        var now = Instant.parse("2026-01-01T00:00:00Z");
        var r = Rejection.capture(UUID.randomUUID(), request, now).queue(now)
                .claim(UUID.randomUUID(), now.plusSeconds(30), now);
        work = new RecoveryWork(r, payload);
    }

    @Test
    void shouldDecodePinnedVersionValidateThenReplayWithStableKey() {
        when(codec.typeId()).thenReturn("customer");
        when(codec.decode("{}", 2)).thenReturn("decoded");
        registry.execute(work);
        var order = inOrder(handler);
        order.verify(handler).validate(eq("decoded"), contextCaptor.capture());
        order.verify(handler).replay(eq("decoded"), eq(contextCaptor.getValue()));
        assertEquals(work.rejection().id(), contextCaptor.getValue().idempotencyKey());
        assertEquals(1, contextCaptor.getValue().attemptNumber());
    }

    @Test
    void shouldPreventReplayWhenValidationFails() {
        when(codec.typeId()).thenReturn("customer");
        when(codec.decode("{}", 2)).thenReturn("decoded");
        doThrow(new RecoveryValidationException()).when(handler).validate(any(), any());
        var failure = assertThrows(HandlerExecutionException.class, () -> registry.execute(work));
        assertEquals(RecoveryFailure.VALIDATION_FAILED, failure.failure());
        verify(handler, never()).replay(any(), any());
    }

    @Test
    void shouldRejectMissingHandlerWithoutInvokingCodec() {
        var empty = new RecoveryHandlerRegistry(List.of());
        assertEquals(RecoveryFailure.HANDLER_UNAVAILABLE,
                assertThrows(HandlerExecutionException.class, () -> empty.execute(work)).failure());
        verifyNoInteractions(codec, handler);
    }

    @Test
    void shouldRejectDuplicateRegistration() {
        var entry = new RecoveryHandlerRegistry.Registration<>("h", codec, handler);
        assertThrows(IllegalArgumentException.class, () -> new RecoveryHandlerRegistry(List.of(entry, entry)));
    }

    @Test
    void shouldSanitizeHandlerException() {
        when(codec.typeId()).thenReturn("customer");
        when(codec.decode("{}", 2)).thenReturn("decoded");
        doThrow(new IllegalStateException("sensitive data")).when(handler).replay(any(), any());
        var failure = assertThrows(HandlerExecutionException.class, () -> registry.execute(work));
        assertEquals(RecoveryFailure.HANDLER_FAILED, failure.failure());
        assertFalse(failure.getMessage().contains("sensitive data"));
        assertNull(failure.getCause());
    }
    @Test
    void shouldRejectIncompatibleTypeWithoutDecodeOrReplay() {
        when(codec.typeId()).thenReturn("different");
        assertEquals(RecoveryFailure.HANDLER_UNAVAILABLE,
                assertThrows(HandlerExecutionException.class, () -> registry.execute(work)).failure());
        verify(codec, never()).decode(anyString(), anyInt());
        verifyNoInteractions(handler);
    }

    @Test
    void shouldRejectNullDecodedPayloadWithoutInvokingHandler() {
        when(codec.typeId()).thenReturn("customer");
        when(codec.decode("{}", 2)).thenReturn(null);
        assertEquals(RecoveryFailure.HANDLER_FAILED,
                assertThrows(HandlerExecutionException.class, () -> registry.execute(work)).failure());
        verifyNoInteractions(handler);
    }

    @Test
    void shouldValidateRegistrationIdentity() {
        for (String id : java.util.Arrays.asList(null, "", " ", "x".repeat(129))) {
            assertThrows(IllegalArgumentException.class, () -> new RecoveryHandlerRegistry.Registration<>(id, codec, handler));
        }
        assertDoesNotThrow(() -> new RecoveryHandlerRegistry.Registration<>("x".repeat(128), codec, handler));
    }
}
