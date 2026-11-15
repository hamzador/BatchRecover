package io.github.hamzador.batchrecover.starter;

import io.github.hamzador.batchrecover.core.domain.RecoveryResult;
import io.github.hamzador.batchrecover.core.service.RecoveryEngine;
import io.micrometer.core.instrument.MeterRegistry;

import java.lang.System.Logger;
import java.time.Duration;
import java.util.Objects;
import java.util.function.Supplier;

/** Host-scheduled facade with optional, bounded-cardinality Micrometer metrics. */
public final class BatchRecoverWorker {
    private static final Logger LOG = System.getLogger(BatchRecoverWorker.class.getName());
    private final RecoveryEngine engine;
    private final Supplier<MeterRegistry> registries;

    public BatchRecoverWorker(RecoveryEngine engine, Supplier<MeterRegistry> registries) {
        this.engine = Objects.requireNonNull(engine);
        this.registries = Objects.requireNonNull(registries);
    }

    /** Perform one host-owned poll and record an outcome metric when available. */
    public RecoveryResult runOnce() {
        long start = System.nanoTime();
        String outcome = "ERROR";
        try {
            RecoveryResult result = engine.runOnce();
            outcome = result.name();
            return result;
        } finally {
            record("batchrecover.worker.poll", outcome, start);
        }
    }

    /** Perform one lease maintenance poll without invoking a handler. */
    public boolean reapOnce() {
        long start = System.nanoTime();
        String outcome = "ERROR";
        try {
            boolean reaped = engine.reapOnce();
            outcome = reaped ? "REAPED" : "NO_WORK";
            return reaped;
        } finally {
            record("batchrecover.worker.reap", outcome, start);
        }
    }

    // A meter failure after a successful commit must not turn it into an apparent recovery failure.
    private void record(String name, String outcome, long start) {
        try {
            MeterRegistry registry = registries.get();
            if (registry != null) {
                registry.timer(name, "outcome", outcome)
                        .record(Duration.ofNanos(Math.max(0, System.nanoTime() - start)));
            }
        } catch (RuntimeException failure) {
            LOG.log(Logger.Level.WARNING, "BatchRecover metric recording failed");
        }
    }
}
