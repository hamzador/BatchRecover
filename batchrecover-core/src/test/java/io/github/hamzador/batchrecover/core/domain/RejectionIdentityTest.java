package io.github.hamzador.batchrecover.core.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RejectionIdentityTest {

    @Test
    void shouldIdentifySameSourceOccurrenceAcrossCaptures() {
        var first = new RejectionIdentity("customer", 1L, "import", "file:42");
        var second = new RejectionIdentity("customer", 1L, "import", "file:42");

        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
    }

    @Test
    void shouldDistinguishDifferentSourceOccurrences() {
        var first = new RejectionIdentity("customer", 1L, "import", "file:42");
        var second = new RejectionIdentity("customer", 1L, "import", "file:43");

        assertNotEquals(first, second);
    }

    @Test
    void shouldRejectBlankSourceOccurrence() {
        assertThrows(IllegalArgumentException.class,
                () -> new RejectionIdentity("customer", 1L, "import", " "));
    }

    @Test
    void shouldRejectNegativeJobInstanceId() {
        assertThrows(IllegalArgumentException.class,
                () -> new RejectionIdentity("customer", -1L, "import", "file:42"));
    }

    @Test
    void shouldRejectNullApplicationScope() {
        assertThrows(NullPointerException.class,
                () -> new RejectionIdentity(null, 1L, "import", "file:42"));
    }
}
