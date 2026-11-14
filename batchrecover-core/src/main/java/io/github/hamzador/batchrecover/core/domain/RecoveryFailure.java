package io.github.hamzador.batchrecover.core.domain;

/** Deliberately fixed sanitized codes, never exception messages or stack traces. */
public enum RecoveryFailure {
    VALIDATION_FAILED, HANDLER_FAILED, HANDLER_UNAVAILABLE, LEASE_EXPIRED
}
