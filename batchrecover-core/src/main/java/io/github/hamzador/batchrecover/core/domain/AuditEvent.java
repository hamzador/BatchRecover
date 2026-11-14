package io.github.hamzador.batchrecover.core.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record AuditEvent(UUID id, UUID rejectionId, long version, AuditAction action,
                         String actor, String reason, RejectionStatus previousStatus,
                         RejectionStatus newStatus, Instant createdAt) {
    public AuditEvent {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(rejectionId, "rejectionId");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(newStatus, "newStatus");
        Objects.requireNonNull(createdAt, "createdAt");
        DomainChecks.nonNegative(version, "version");
        DomainChecks.text(actor, "actor", 128);
        DomainChecks.text(reason, "reason", 512);
        if (action == AuditAction.CAPTURED) {
            if (previousStatus != null || newStatus != RejectionStatus.PENDING || version != 0) {
                throw new IllegalArgumentException("Invalid capture audit");
            }
        } else {
            Objects.requireNonNull(previousStatus, "previousStatus").requireTransitionTo(newStatus);
            if (version == 0) {
                throw new IllegalArgumentException("Mutation requires a positive version");
            }
        }
    }
}
