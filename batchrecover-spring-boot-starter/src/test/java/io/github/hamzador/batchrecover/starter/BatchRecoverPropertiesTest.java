package io.github.hamzador.batchrecover.starter;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;

class BatchRecoverPropertiesTest {
    private final BatchRecoverProperties.Storage storage = new BatchRecoverProperties.Storage(
            BatchRecoverProperties.StorageType.MEMORY, 1000, 8, 16777216, "auto");

    @Test
    void shouldValidateCaptureBudgetAndWorkerSettings() {
        assertDoesNotThrow(() -> properties(false, 1, null));
        assertDoesNotThrow(() -> properties(true, 100, "app"));
        assertThrows(IllegalArgumentException.class, () -> properties(true, 0, "app"));
        assertThrows(IllegalArgumentException.class, () -> properties(true, 101, "app"));
        assertThrows(IllegalArgumentException.class, () -> properties(true, 3, null));
        assertEquals("app", properties(true, 3, "app").workerSettings().applicationScope());
    }

    @Test
    void shouldValidateDialectNameWithoutOpeningDatabase() {
        for (String value : java.util.Arrays.asList(null, "", "bad name", "x".repeat(65))) {
            assertThrows(IllegalArgumentException.class, () -> new BatchRecoverProperties.Storage(
                    BatchRecoverProperties.StorageType.JDBC, 1, 1, 128, value));
        }
        assertEquals("CUSTOM_1", new BatchRecoverProperties.Storage(
                BatchRecoverProperties.StorageType.JDBC, 1, 1, 128, "CUSTOM_1").dialect());
    }

    private BatchRecoverProperties properties(boolean enabled, int attempts, String scope) {
        return new BatchRecoverProperties(enabled, scope, attempts, Duration.ofSeconds(30),
                Duration.ofSeconds(1), Duration.ofMinutes(1), true, storage);
    }
}
