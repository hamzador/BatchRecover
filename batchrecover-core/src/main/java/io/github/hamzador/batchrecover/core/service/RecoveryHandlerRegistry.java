package io.github.hamzador.batchrecover.core.service;

import io.github.hamzador.batchrecover.core.domain.*;
import io.github.hamzador.batchrecover.core.exception.*;
import io.github.hamzador.batchrecover.core.port.PayloadCodec;
import io.github.hamzador.batchrecover.core.port.RecoveryHandler;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable, explicit registration; no reflective loading of handler or payload classes. */
public final class RecoveryHandlerRegistry {
    private final Map<String, Registration<?>> handlers;

    public RecoveryHandlerRegistry(List<Registration<?>> registrations) {
        var map = new HashMap<String, Registration<?>>();
        for (var registration : registrations) {
            if (map.putIfAbsent(registration.handlerId(), registration) != null) {
                throw new IllegalArgumentException("Duplicate handler ID");
            }
        }
        handlers = Map.copyOf(map);
    }

    public void execute(RecoveryWork work) {
        var registration = handlers.get(work.rejection().handlerId());
        if (registration == null || !registration.codec().typeId().equals(work.payload().typeId())) {
            throw new HandlerExecutionException(RecoveryFailure.HANDLER_UNAVAILABLE);
        }
        try {
            registration.execute(work);
        } catch (RecoveryValidationException failure) {
            throw new HandlerExecutionException(RecoveryFailure.VALIDATION_FAILED);
        } catch (RuntimeException failure) {
            throw new HandlerExecutionException(RecoveryFailure.HANDLER_FAILED);
        }
    }

    public record Registration<T>(String handlerId, PayloadCodec<T> codec, RecoveryHandler<T> handler) {
        public Registration {
            if (handlerId == null || handlerId.isBlank() || handlerId.length() > 128) {
                throw new IllegalArgumentException("Invalid handler ID");
            }
            Objects.requireNonNull(codec);
            Objects.requireNonNull(handler);
        }

        private void execute(RecoveryWork work) {
            T item = Objects.requireNonNull(codec.decode(work.payload().encoded(), work.payload().schemaVersion()));
            RecoveryContext context = work.context();
            handler.validate(item, context);
            handler.replay(item, context);
        }
    }
}
