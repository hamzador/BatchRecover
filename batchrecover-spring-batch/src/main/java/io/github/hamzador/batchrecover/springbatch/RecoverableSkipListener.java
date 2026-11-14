package io.github.hamzador.batchrecover.springbatch;

import io.github.hamzador.batchrecover.core.domain.CaptureRequest;
import io.github.hamzador.batchrecover.core.domain.ReplayPayload;
import io.github.hamzador.batchrecover.core.port.PayloadCodec;
import io.github.hamzador.batchrecover.core.port.RecordIdentity;
import io.github.hamzador.batchrecover.core.service.RejectionService;
import org.springframework.batch.core.listener.SkipListener;

import java.util.Objects;

/** Explicit opt-in listener for sequential processor skips; transactional capture is the default. */
public final class RecoverableSkipListener<I, O> implements SkipListener<I, O> {
    private final RejectionService service;
    private final BatchCaptureContextResolver contextResolver;
    private final RecordIdentity<I> identity;
    private final PayloadCodec<I> codec;
    private final ErrorClassifier classifier;
    private final CaptureSettings settings;

    public RecoverableSkipListener(RejectionService service, BatchCaptureContextResolver contextResolver,
                                   RecordIdentity<I> identity, PayloadCodec<I> codec,
                                   ErrorClassifier classifier, CaptureSettings settings) {
        this.service = Objects.requireNonNull(service);
        this.contextResolver = Objects.requireNonNull(contextResolver);
        this.identity = Objects.requireNonNull(identity);
        this.codec = Objects.requireNonNull(codec);
        this.classifier = Objects.requireNonNull(classifier);
        this.settings = Objects.requireNonNull(settings);
    }

    @Override
    public void onSkipInProcess(I item, Throwable failure) {
        var context = contextResolver.current();
        var error = classifier.classify(Objects.requireNonNull(failure));
        var payload = new ReplayPayload(codec.typeId(), codec.schemaVersion(), codec.encode(item));
        var request = new CaptureRequest(context.identity(settings.applicationScope(),
                identity.sourceOccurrenceId(item)), context.jobExecutionId(), context.stepExecutionId(),
                settings.handlerId(), error.code(), error.category(), payload, settings.maxAttempts());
        // MANDATORY at the JDBC repository: fail closed rather than record a detached skip.
        if (settings.transactional()) {
            service.captureInExistingTransaction(request);
        } else {
            service.capture(request);
        }
    }

    // Read and write skips are intentionally not captured in this release.
}
