-- Scoped, nonpartial indexes for engines without PostgreSQL partial indexes.
CREATE INDEX br_rejection_scope_due_idx ON br_rejection(application_scope, status, next_attempt_at, id);
CREATE INDEX br_rejection_scope_lease_idx ON br_rejection(application_scope, status, lease_expires_at, id);
