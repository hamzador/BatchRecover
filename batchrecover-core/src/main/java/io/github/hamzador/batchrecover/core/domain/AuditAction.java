package io.github.hamzador.batchrecover.core.domain;

public enum AuditAction {
    CAPTURED, CORRECTED, QUEUED, DISCARDED, CLAIMED, RECOVERED, RETRY_SCHEDULED, DEAD, VALIDATION_FAILED
}
