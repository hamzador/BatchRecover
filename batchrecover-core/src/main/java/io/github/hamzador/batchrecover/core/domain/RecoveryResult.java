package io.github.hamzador.batchrecover.core.domain;

public enum RecoveryResult {
    NO_WORK, RECOVERED, STALE_CLAIM, RETRY_SCHEDULED, CORRECTION_REQUIRED, DEAD
}
