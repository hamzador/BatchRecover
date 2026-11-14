-- Scope worker scans by the application that owns the handler registry.
CREATE INDEX br_rejection_scope_due_idx ON br_rejection(application_scope, next_attempt_at, id)
    WHERE status = 'QUEUED';
CREATE INDEX br_rejection_scope_lease_idx ON br_rejection(application_scope, lease_expires_at, id)
    WHERE status = 'RUNNING';
