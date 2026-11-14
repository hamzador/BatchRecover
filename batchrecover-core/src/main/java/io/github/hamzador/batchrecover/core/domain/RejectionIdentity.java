package io.github.hamzador.batchrecover.core.domain;

import java.util.Objects;

/** Stable capture identity; execution IDs are provenance, not identity. */
public record RejectionIdentity(
        String applicationScope,
        long jobInstanceId,
        String stepName,
        String sourceOccurrenceId) {

    public RejectionIdentity {
        requireText(applicationScope, "applicationScope");
        if (jobInstanceId < 0) {
            throw new IllegalArgumentException("jobInstanceId must be non-negative");
        }
        requireText(stepName, "stepName");
        requireText(sourceOccurrenceId, "sourceOccurrenceId");
    }

    private static void requireText(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
