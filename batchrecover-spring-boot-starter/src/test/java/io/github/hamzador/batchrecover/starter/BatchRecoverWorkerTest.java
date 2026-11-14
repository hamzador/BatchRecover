package io.github.hamzador.batchrecover.starter;

import io.github.hamzador.batchrecover.core.domain.RecoveryResult;
import io.github.hamzador.batchrecover.core.service.RecoveryEngine;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BatchRecoverWorkerTest {
    @Mock private RecoveryEngine engine;
    @Mock private Supplier<MeterRegistry> registries;
    @InjectMocks private BatchRecoverWorker worker;
    private SimpleMeterRegistry registry;

    @BeforeEach
    void setUp() { registry = new SimpleMeterRegistry(); }

    @AfterEach
    void tearDown() { registry.close(); }

    @Test
    void shouldRecordSuccessfulPollWithBoundedOutcomeTag() {
        when(registries.get()).thenReturn(registry);
        when(engine.runOnce()).thenReturn(RecoveryResult.RECOVERED);
        assertEquals(RecoveryResult.RECOVERED, worker.runOnce());
        assertEquals(1L, registry.get("batchrecover.worker.poll").tag("outcome", "RECOVERED").timer().count());
    }

    @Test
    void shouldPreserveEngineExceptionAndRecordError() {
        when(registries.get()).thenReturn(registry);
        var failure = new IllegalStateException("database");
        when(engine.runOnce()).thenThrow(failure);
        assertSame(failure, assertThrows(IllegalStateException.class, worker::runOnce));
        assertEquals(1L, registry.get("batchrecover.worker.poll").tag("outcome", "ERROR").timer().count());
    }

    @Test
    void shouldRecordExplicitReaping() {
        when(registries.get()).thenReturn(registry);
        when(engine.reapOnce()).thenReturn(true);
        assertTrue(worker.reapOnce());
        assertEquals(1L, registry.get("batchrecover.worker.reap").tag("outcome", "REAPED").timer().count());
    }

    @Test
    void shouldNotChangeResultWhenMetricRecordingFails() {
        when(registries.get()).thenThrow(new IllegalStateException("metrics offline"));
        when(engine.runOnce()).thenReturn(RecoveryResult.RECOVERED);
        assertEquals(RecoveryResult.RECOVERED, worker.runOnce());
    }
}
