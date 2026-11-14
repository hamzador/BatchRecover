package io.github.hamzador.batchrecover.core.domain;

import java.util.Objects;

/** The caller must supply a safe payload and sanitized error code (not exception messages). */
public record CaptureRequest(RejectionIdentity identity, long jobExecutionId,
                             long stepExecutionId, String handlerId, String errorCode,
                             ErrorCategory category, ReplayPayload payload, int maxAttempts) {
    public CaptureRequest {
        Objects.requireNonNull(identity, "identity");
        DomainChecks.nonNegative(jobExecutionId, "jobExecutionId");
        DomainChecks.nonNegative(stepExecutionId, "stepExecutionId");
        DomainChecks.text(handlerId, "handlerId", 128);
        DomainChecks.text(errorCode, "errorCode", 128);
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(payload, "payload");
        if (maxAttempts < 1 || maxAttempts > 100) {
            throw new IllegalArgumentException("maxAttempts must be between 1 and 100");
        }
    }
}
