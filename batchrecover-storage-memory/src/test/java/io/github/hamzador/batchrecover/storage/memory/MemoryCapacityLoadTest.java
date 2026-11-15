package io.github.hamzador.batchrecover.storage.memory;

import io.github.hamzador.batchrecover.core.domain.*;
import io.github.hamzador.batchrecover.core.service.RejectionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.time.Clock;
import java.time.Duration;
import java.util.HashSet;
import static org.junit.jupiter.api.Assertions.*;

class MemoryCapacityLoadTest {
    @Test
    @Timeout(30)
    void shouldRetainThousandRecordsAndDrainQueueWithoutEviction() {
        var clock = Clock.systemUTC();
        var storage = new InMemoryRecoveryStorage(clock, new MemoryStorageLimits(1000, 2, 128_000));
        var service = new RejectionService(storage, clock);
        var ids = new HashSet<java.util.UUID>();
        for (int i = 0; i < 1000; i++) {
            var request = new CaptureRequest(new RejectionIdentity("app", 1, "step", "row:" + i),
                    2, 3, "handler", "ERROR", ErrorCategory.BUSINESS,
                    new ReplayPayload("type", 1, "invalid"), 2);
            var r = service.capture(request);
            assertEquals(r, service.capture(request));
            ids.add(r.id());
            var corrected = service.correct(r.id(), 0, new ReplayPayload("type", 1, "fixed"), "operator", "fix");
            service.queue(r.id(), corrected.version(), "operator", "replay");
        }
        var seen = new HashSet<java.util.UUID>();
        for (int i = 0; i < 1000; i++) {
            var claim = storage.claimNext("app", Duration.ofSeconds(30)).orElseThrow();
            assertTrue(storage.execute(claim, work -> {
                assertTrue(seen.add(work.context().idempotencyKey()));
                assertEquals("fixed", work.payload().encoded());
            }));
        }
        assertEquals(ids, seen);
        assertTrue(storage.claimNext("app", Duration.ofSeconds(30)).isEmpty());
        for (var id : ids) {
            assertEquals(RejectionStatus.RECOVERED, service.get(id).status());
            assertEquals(2, storage.findRevisions(id).size());
            assertEquals(5, storage.findAuditEvents(id).size());
        }
        assertThrows(StorageCapacityExceededException.class, () -> service.capture(
                new CaptureRequest(new RejectionIdentity("app", 1, "step", "extra"), 2, 3, "handler",
                        "ERROR", ErrorCategory.BUSINESS, new ReplayPayload("type", 1, "extra"), 2)));
    }
}
