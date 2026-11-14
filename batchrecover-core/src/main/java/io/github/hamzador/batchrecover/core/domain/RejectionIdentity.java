package io.github.hamzador.batchrecover.core.domain;

import java.util.Objects;

/** Stable identity; execution IDs are provenance, not identity. */
public record RejectionIdentity(String applicationScope, long jobInstanceId,
                                String stepName, String sourceOccurrenceId,
                                RejectionPhase phase) {
    public RejectionIdentity(String applicationScope, long jobInstanceId,
                             String stepName, String sourceOccurrenceId) {
        this(applicationScope, jobInstanceId, stepName, sourceOccurrenceId, RejectionPhase.PROCESS);
    }

    public RejectionIdentity {
        DomainChecks.text(applicationScope, "applicationScope", 128);
        DomainChecks.nonNegative(jobInstanceId, "jobInstanceId");
        DomainChecks.text(stepName, "stepName", 128);
        DomainChecks.text(sourceOccurrenceId, "sourceOccurrenceId", 512);
        Objects.requireNonNull(phase, "phase");
    }
}
