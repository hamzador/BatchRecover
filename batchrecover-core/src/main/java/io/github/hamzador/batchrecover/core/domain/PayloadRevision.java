package io.github.hamzador.batchrecover.core.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record PayloadRevision(UUID rejectionId, int revision, ReplayPayload payload,
                              String actor, String reason, Instant createdAt) {
    public PayloadRevision {
        Objects.requireNonNull(rejectionId, "rejectionId");
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(createdAt, "createdAt");
        DomainChecks.nonNegative(revision, "revision");
        DomainChecks.text(actor, "actor", 128);
        DomainChecks.text(reason, "reason", 512);
    }
}
