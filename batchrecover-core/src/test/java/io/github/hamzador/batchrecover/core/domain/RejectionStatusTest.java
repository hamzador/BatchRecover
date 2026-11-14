package io.github.hamzador.batchrecover.core.domain;

import io.github.hamzador.batchrecover.core.exception.InvalidStatusTransitionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class RejectionStatusTest {
    @Test
    void shouldAllowOnlyDocumentedTransitions() {
        var allowed = Set.of("PENDING:PENDING", "PENDING:QUEUED", "PENDING:DISCARDED",
                "QUEUED:RUNNING", "QUEUED:DISCARDED", "RUNNING:RECOVERED",
                "RUNNING:QUEUED", "RUNNING:DEAD", "RUNNING:PENDING");
        for (var from : RejectionStatus.values()) {
            for (var to : RejectionStatus.values()) {
                if (allowed.contains(from + ":" + to)) {
                    assertDoesNotThrow(() -> from.requireTransitionTo(to));
                } else {
                    assertThrows(InvalidStatusTransitionException.class, () -> from.requireTransitionTo(to));
                }
            }
        }
    }

    @ParameterizedTest
    @EnumSource(value = RejectionStatus.class, names = {"RECOVERED", "DEAD", "DISCARDED"})
    void shouldRejectReopeningTerminalStatus(RejectionStatus status) {
        assertThrows(InvalidStatusTransitionException.class,
                () -> status.requireTransitionTo(RejectionStatus.PENDING));
    }
}
