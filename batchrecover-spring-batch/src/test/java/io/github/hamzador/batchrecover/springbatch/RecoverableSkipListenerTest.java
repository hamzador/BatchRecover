package io.github.hamzador.batchrecover.springbatch;

import io.github.hamzador.batchrecover.core.domain.CaptureRequest;
import io.github.hamzador.batchrecover.core.domain.ErrorCategory;
import io.github.hamzador.batchrecover.core.port.PayloadCodec;
import io.github.hamzador.batchrecover.core.port.RecordIdentity;
import io.github.hamzador.batchrecover.core.service.RejectionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RecoverableSkipListenerTest {
    @Mock private RejectionService service;
    @Mock private BatchCaptureContextResolver contextResolver;
    @Mock private RecordIdentity<String> identity;
    @Mock private PayloadCodec<String> codec;
    @Mock private ErrorClassifier classifier;
    @Spy private CaptureSettings settings = new CaptureSettings("app", "customer", 3);
    @InjectMocks private RecoverableSkipListener<String, String> listener;
    @Captor private ArgumentCaptor<CaptureRequest> requestCaptor;

    @Test
    void shouldCaptureProcessorSkipWithSafePayloadAndProvenance() {
        var failure = new IllegalArgumentException("sensitive message");
        when(contextResolver.current()).thenReturn(new BatchCaptureContext(11, 22, 33, "import"));
        when(identity.sourceOccurrenceId("input")).thenReturn("file:42");
        when(codec.typeId()).thenReturn("customer");
        when(codec.schemaVersion()).thenReturn(1);
        when(codec.encode("input")).thenReturn("{}");
        when(classifier.classify(failure)).thenReturn(new ClassifiedError("INVALID", ErrorCategory.BUSINESS));

        listener.onSkipInProcess("input", failure);

        verify(service).captureInExistingTransaction(requestCaptor.capture());
        var request = requestCaptor.getValue();
        assertEquals(11, request.identity().jobInstanceId());
        assertEquals(22, request.jobExecutionId());
        assertEquals(33, request.stepExecutionId());
        assertEquals("file:42", request.identity().sourceOccurrenceId());
        assertEquals("INVALID", request.errorCode());
        assertEquals("{}", request.payload().encoded());
        verify(service, never()).capture(any());
    }

    @Test
    void shouldCaptureWithoutTransactionOnlyWhenExplicitlyConfigured() {
        var failure = new IllegalArgumentException();
        when(settings.transactional()).thenReturn(false);
        when(contextResolver.current()).thenReturn(new BatchCaptureContext(11, 22, 33, "import"));
        when(identity.sourceOccurrenceId("input")).thenReturn("file:42");
        when(codec.typeId()).thenReturn("customer");
        when(codec.schemaVersion()).thenReturn(1);
        when(codec.encode("input")).thenReturn("{}");
        when(classifier.classify(failure)).thenReturn(new ClassifiedError("INVALID", ErrorCategory.BUSINESS));
        listener.onSkipInProcess("input", failure);
        verify(service).capture(requestCaptor.capture());
        assertEquals("file:42", requestCaptor.getValue().identity().sourceOccurrenceId());
        verify(service, never()).captureInExistingTransaction(any());
    }

    @Test
    void shouldIgnoreReadAndWriteSkips() {
        listener.onSkipInRead(new IllegalArgumentException());
        listener.onSkipInWrite("output", new IllegalArgumentException());
        verifyNoInteractions(service, contextResolver, codec, classifier, identity);
    }

    @Test
    void shouldPropagateCaptureFailure() {
        var failure = new IllegalArgumentException();
        when(contextResolver.current()).thenReturn(new BatchCaptureContext(11, 22, 33, "import"));
        when(identity.sourceOccurrenceId("input")).thenReturn("file:42");
        when(codec.typeId()).thenReturn("customer");
        when(codec.schemaVersion()).thenReturn(1);
        when(codec.encode("input")).thenReturn("{}");
        when(classifier.classify(failure)).thenReturn(new ClassifiedError("INVALID", ErrorCategory.BUSINESS));
        when(service.captureInExistingTransaction(any())).thenThrow(new IllegalStateException("storage unavailable"));
        assertThrows(IllegalStateException.class, () -> listener.onSkipInProcess("input", failure));
    }
}
