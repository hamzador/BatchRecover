package io.github.hamzador.batchrecover.benchmarks;

import io.github.hamzador.batchrecover.core.domain.*;
import io.github.hamzador.batchrecover.core.service.RejectionService;
import io.github.hamzador.batchrecover.storage.memory.InMemoryRecoveryStorage;
import io.github.hamzador.batchrecover.storage.memory.MemoryStorageLimits;
import org.openjdk.jmh.annotations.*;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/** Benchmark only, not a production module. No database or external I/O. */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
public class MemoryRecoveryBenchmark {
    @State(Scope.Thread)
    public static class CaptureState {
        private RejectionService service;
        private CaptureRequest[] requests;

        @Setup(Level.Trial)
        public void createRequests() {
            requests = new CaptureRequest[100];
            for (int i = 0; i < requests.length; i++) requests[i] = request(i);
        }

        @Setup(Level.Invocation)
        public void reset() {
            var clock = Clock.systemUTC();
            service = new RejectionService(new InMemoryRecoveryStorage(clock, MemoryStorageLimits.DEFAULT), clock);
        }
    }

    @State(Scope.Thread)
    public static class ReplayState {
        private InMemoryRecoveryStorage storage;

        @Setup(Level.Invocation)
        public void reset() {
            var clock = Clock.systemUTC();
            storage = new InMemoryRecoveryStorage(clock, MemoryStorageLimits.DEFAULT);
            var service = new RejectionService(storage, clock);
            for (int i = 0; i < 100; i++) {
                var r = service.capture(request(i));
                service.queue(r.id(), r.version(), "operator", "replay");
            }
        }
    }

    @Benchmark
    @OperationsPerInvocation(100)
    public long captureBatch(CaptureState state) {
        long versionSum = 0;
        for (var request : state.requests) versionSum += state.service.capture(request).id().getLeastSignificantBits();
        return versionSum;
    }

    @Benchmark
    @OperationsPerInvocation(100)
    public int replayBatch(ReplayState state) {
        int recovered = 0;
        for (int i = 0; i < 100; i++) {
            var claim = state.storage.claimNext("app", Duration.ofSeconds(30)).orElseThrow();
            if (state.storage.execute(claim, work -> {
                if (!work.payload().encoded().equals("invalid")) throw new IllegalStateException("unexpected payload");
            })) recovered++;
        }
        if (recovered != 100) throw new IllegalStateException("incomplete replay");
        return recovered;
    }

    private static CaptureRequest request(int row) {
        return new CaptureRequest(new RejectionIdentity("app", 1, "step", "row:" + row),
                2, 3, "handler", "INVALID", ErrorCategory.BUSINESS,
                new ReplayPayload("type", 1, "invalid"), 3);
    }
}
