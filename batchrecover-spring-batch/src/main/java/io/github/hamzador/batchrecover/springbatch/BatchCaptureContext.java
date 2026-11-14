package io.github.hamzador.batchrecover.springbatch;

import io.github.hamzador.batchrecover.core.domain.RejectionIdentity;

/** Immutable provenance derived from the current Spring Batch step execution. */
public record BatchCaptureContext(long jobInstanceId, long jobExecutionId,
                                  long stepExecutionId, String stepName) {
    public RejectionIdentity identity(String applicationScope, String occurrenceId) {
        return new RejectionIdentity(applicationScope, jobInstanceId, stepName, occurrenceId);
    }
}
