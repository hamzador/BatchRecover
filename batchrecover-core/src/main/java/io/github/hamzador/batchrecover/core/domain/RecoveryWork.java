package io.github.hamzador.batchrecover.core.domain;

public record RecoveryWork(Rejection rejection, ReplayPayload payload) {
    public RecoveryContext context() {
        return RecoveryContext.from(rejection);
    }
}
