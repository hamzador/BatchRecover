package io.github.hamzador.batchrecover.core.domain;

/** Lifecycle states for a rejected record. Transition enforcement is a later milestone. */
public enum RejectionStatus {
    PENDING,
    QUEUED,
    RUNNING,
    RECOVERED,
    DEAD,
    DISCARDED
}
