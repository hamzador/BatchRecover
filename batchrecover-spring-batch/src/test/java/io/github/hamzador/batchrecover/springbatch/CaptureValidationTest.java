package io.github.hamzador.batchrecover.springbatch;

import io.github.hamzador.batchrecover.core.domain.ErrorCategory;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CaptureValidationTest {
    @Test
    void shouldValidateAllCaptureSettingsIndependently() {
        var scope = "a".repeat(128);
        var handler = "h".repeat(128);
        assertTrue(new CaptureSettings(scope, handler, 1).transactional());
        assertFalse(new CaptureSettings(scope, handler, 100, false).transactional());
        for (String value : java.util.Arrays.asList(null, "", " ", "a".repeat(129))) {
            assertThrows(IllegalArgumentException.class, () -> new CaptureSettings(value, handler, 1));
            assertThrows(IllegalArgumentException.class, () -> new CaptureSettings(scope, value, 1));
        }
        assertThrows(IllegalArgumentException.class, () -> new CaptureSettings(scope, handler, 0));
        assertThrows(IllegalArgumentException.class, () -> new CaptureSettings(scope, handler, 101));
    }

    @Test
    void shouldAcceptOnlyBoundedSanitizedErrorCodes() {
        assertEquals("ERROR_1.A-B", new ClassifiedError("ERROR_1.A-B", ErrorCategory.TECHNICAL).code());
        assertDoesNotThrow(() -> new ClassifiedError("X".repeat(128), ErrorCategory.UNKNOWN));
        for (String value : java.util.Arrays.asList(null, "", "two words", "lowercase", "X".repeat(129))) {
            assertThrows(IllegalArgumentException.class, () -> new ClassifiedError(value, ErrorCategory.UNKNOWN));
        }
        assertThrows(NullPointerException.class, () -> new ClassifiedError("ERROR", null));
    }
}
