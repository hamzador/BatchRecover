package io.github.hamzador.batchrecover.core.domain;

import java.util.Objects;

final class DomainChecks {
    private DomainChecks() {}

    static String text(String value, String field, int maxLength) {
        Objects.requireNonNull(value, field);
        if (value.isBlank() || value.length() > maxLength || value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Invalid " + field);
        }
        return value;
    }

    static void nonNegative(long value, String field) {
        if (value < 0) {
            throw new IllegalArgumentException(field + " must be non-negative");
        }
    }
}
