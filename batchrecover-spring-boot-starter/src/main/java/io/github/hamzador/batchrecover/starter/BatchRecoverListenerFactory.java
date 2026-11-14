package io.github.hamzador.batchrecover.starter;

import io.github.hamzador.batchrecover.core.port.PayloadCodec;
import io.github.hamzador.batchrecover.core.port.RecordIdentity;
import io.github.hamzador.batchrecover.core.service.RejectionService;
import io.github.hamzador.batchrecover.springbatch.*;

import java.util.Objects;

/** Create an explicitly enrolled processor-skip listener using the configured application scope. */
public final class BatchRecoverListenerFactory {
    private final RejectionService service;
    private final BatchCaptureContextResolver resolver;
    private final BatchRecoverProperties properties;

    public BatchRecoverListenerFactory(RejectionService service, BatchCaptureContextResolver resolver,
                                       BatchRecoverProperties properties) {
        this.service = Objects.requireNonNull(service);
        this.resolver = Objects.requireNonNull(resolver);
        this.properties = Objects.requireNonNull(properties);
    }

    public <I,O> RecoverableSkipListener<I,O> create(String handlerId, RecordIdentity<I> identity,
                                                    PayloadCodec<I> codec, ErrorClassifier classifier) {
        return new RecoverableSkipListener<>(service, resolver, identity, codec, classifier,
                new CaptureSettings(properties.applicationScope(), handlerId, properties.maxAttempts()));
    }
}
