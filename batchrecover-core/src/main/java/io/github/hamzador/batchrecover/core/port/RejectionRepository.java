package io.github.hamzador.batchrecover.core.port;

import io.github.hamzador.batchrecover.core.domain.*;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Each write atomically persists the rejection, associated revision (if any) and audit. */
public interface RejectionRepository {
    Rejection capture(Rejection rejection, PayloadRevision original, AuditEvent audit);
    Optional<Rejection> findById(UUID id);
    List<PayloadRevision> findRevisions(UUID id);
    List<AuditEvent> findAuditEvents(UUID id);
    Rejection save(Rejection updated, long expectedVersion,
                   Optional<PayloadRevision> revision, AuditEvent audit);
}
