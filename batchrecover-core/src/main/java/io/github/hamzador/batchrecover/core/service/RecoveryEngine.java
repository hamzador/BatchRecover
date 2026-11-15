package io.github.hamzador.batchrecover.core.service;

import io.github.hamzador.batchrecover.core.domain.*;
import io.github.hamzador.batchrecover.core.exception.HandlerExecutionException;
import io.github.hamzador.batchrecover.core.port.RecoveryRepository;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

/** Thread-safe polling entry point; host owns scheduling and worker count. */
public final class RecoveryEngine {
    private final RecoveryRepository repository;
    private final RecoveryHandlerRegistry registry;
    private final RecoverySettings settings;

    public RecoveryEngine(RecoveryRepository repository, RecoveryHandlerRegistry registry,
                          RecoverySettings settings) {
        this.repository = Objects.requireNonNull(repository);
        this.registry = Objects.requireNonNull(registry);
        this.settings = Objects.requireNonNull(settings);
    }

    /** Reap at most one expired claim and process at most one eligible record outside an ambient transaction. NO_WORK is not proof that delayed or contended work is absent. */
    public RecoveryResult runOnce() {
        reapOnce();
        var claim = repository.claimNext(settings.applicationScope(), settings.leaseDuration());
        if (claim.isEmpty()) return RecoveryResult.NO_WORK;
        return execute(claim.orElseThrow());
    }

    /** Reap one expired claim without executing a handler; host owns scheduling. */
    public boolean reapOnce() {
        return repository.reapExpired(settings.applicationScope(), this::jitteredDelay);
    }

    private RecoveryResult execute(RecoveryClaim claim) {
        // Only a handler exception is classified. Transactional repositories roll back effects; others cannot. Infrastructure/commit failures
        // propagate; an ambiguous commit must not be incorrectly recorded as a handler failure.
        try {
            return repository.execute(claim, registry::execute)
                    ? RecoveryResult.RECOVERED : RecoveryResult.STALE_CLAIM;
        } catch (HandlerExecutionException failure) {
            return repository.fail(claim, failure.failure(), jitteredDelay(claim.attemptNumber()))
                    .map(status -> switch (status) {
                        case PENDING -> RecoveryResult.CORRECTION_REQUIRED;
                        case QUEUED -> RecoveryResult.RETRY_SCHEDULED;
                        case DEAD -> RecoveryResult.DEAD;
                        default -> throw new IllegalStateException("Unexpected failure status");
                    }).orElse(RecoveryResult.STALE_CLAIM);
        }
    }

    private Duration jitteredDelay(int attempt) {
        return settings.retryPolicy().delayWithJitter(attempt, ThreadLocalRandom.current().nextDouble());
    }
}
