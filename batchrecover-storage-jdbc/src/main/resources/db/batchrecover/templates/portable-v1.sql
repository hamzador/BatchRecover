-- Portable schema: UTC epoch microseconds, UUID text, and canonical identity digest.
CREATE TABLE br_rejection (
    id @UUID@ PRIMARY KEY,
    identity_hash @VARCHAR@(64) NOT NULL,
    application_scope @VARCHAR@(128) NOT NULL,
    job_instance_id @LONG@ NOT NULL CHECK (job_instance_id >= 0),
    job_execution_id @LONG@ NOT NULL CHECK (job_execution_id >= 0),
    step_execution_id @LONG@ NOT NULL CHECK (step_execution_id >= 0),
    step_name @VARCHAR@(128) NOT NULL,
    source_occurrence_id @VARCHAR@(512) NOT NULL,
    phase @VARCHAR@(16) NOT NULL CHECK (phase = 'PROCESS'),
    handler_id @VARCHAR@(128) NOT NULL,
    error_code @VARCHAR@(128) NOT NULL,
    category @VARCHAR@(16) NOT NULL CHECK (category IN ('BUSINESS', 'TECHNICAL', 'UNKNOWN')),
    payload_type @VARCHAR@(128) NOT NULL,
    status @VARCHAR@(16) NOT NULL CHECK (status IN ('PENDING','QUEUED','RUNNING','RECOVERED','DEAD','DISCARDED')),
    current_revision @INTEGER@ NOT NULL CHECK (current_revision >= 0),
    attempt_count @INTEGER@ NOT NULL CHECK (attempt_count >= 0),
    max_attempts @INTEGER@ NOT NULL CHECK (max_attempts BETWEEN 1 AND 100),
    next_attempt_at @LONG@,
    lease_token @UUID@,
    lease_expires_at @LONG@,
    version @LONG@ NOT NULL CHECK (version >= 0),
    created_at @LONG@ NOT NULL,
    updated_at @LONG@ NOT NULL CHECK (updated_at >= created_at),
    CONSTRAINT br_rejection_identity UNIQUE (identity_hash),
    CHECK (attempt_count <= max_attempts),
    CHECK (status NOT IN ('PENDING','QUEUED') OR attempt_count < max_attempts),
    CHECK ((status = 'QUEUED' AND next_attempt_at IS NOT NULL) OR (status <> 'QUEUED' AND next_attempt_at IS NULL)),
    CHECK ((status = 'RUNNING' AND lease_token IS NOT NULL) OR (status <> 'RUNNING' AND lease_token IS NULL)),
    CHECK ((status = 'RUNNING' AND lease_expires_at IS NOT NULL) OR (status <> 'RUNNING' AND lease_expires_at IS NULL))
)@TABLE_OPTIONS@;

CREATE TABLE br_payload_revision (
    rejection_id @UUID@ NOT NULL,
    revision @INTEGER@ NOT NULL CHECK (revision >= 0),
    payload_type @VARCHAR@(128) NOT NULL,
    schema_version @INTEGER@ NOT NULL CHECK (schema_version > 0),
    encoded_payload @PAYLOAD@ NOT NULL@PAYLOAD_CHECK@,
    actor @VARCHAR@(128) NOT NULL,
    reason @VARCHAR@(512) NOT NULL,
    created_at @LONG@ NOT NULL,
    FOREIGN KEY (rejection_id) REFERENCES br_rejection(id),
    PRIMARY KEY (rejection_id, revision)
)@TABLE_OPTIONS@;

CREATE TABLE br_audit_event (
    id @UUID@ PRIMARY KEY,
    rejection_id @UUID@ NOT NULL,
    version @LONG@ NOT NULL CHECK (version >= 0),
    action @VARCHAR@(32) NOT NULL CHECK (action IN ('CAPTURED','CORRECTED','QUEUED','DISCARDED',
        'CLAIMED','RECOVERED','RETRY_SCHEDULED','DEAD','VALIDATION_FAILED')),
    actor @VARCHAR@(128) NOT NULL,
    reason @VARCHAR@(512) NOT NULL,
    previous_status @VARCHAR@(16) CHECK (previous_status IN ('PENDING','QUEUED','RUNNING','RECOVERED','DEAD','DISCARDED')),
    new_status @VARCHAR@(16) NOT NULL CHECK (new_status IN ('PENDING','QUEUED','RUNNING','RECOVERED','DEAD','DISCARDED')),
    created_at @LONG@ NOT NULL,
    FOREIGN KEY (rejection_id) REFERENCES br_rejection(id),
    UNIQUE (rejection_id, version)
)@TABLE_OPTIONS@;

CREATE TABLE br_recovery_attempt (
    id @UUID@ PRIMARY KEY,
    rejection_id @UUID@ NOT NULL,
    revision @INTEGER@ NOT NULL,
    attempt_number @INTEGER@ NOT NULL CHECK (attempt_number > 0),
    lease_token @UUID@ NOT NULL UNIQUE,
    started_at @LONG@ NOT NULL,
    finished_at @LONG@,
    outcome @VARCHAR@(32) NOT NULL CHECK (outcome IN ('RUNNING','RECOVERED','RETRYABLE_FAILURE','VALIDATION_FAILURE','EXPIRED','DEAD')),
    error_code @VARCHAR@(128),
    FOREIGN KEY (rejection_id, revision) REFERENCES br_payload_revision(rejection_id, revision),
    UNIQUE (rejection_id, attempt_number),
    CHECK ((outcome = 'RUNNING' AND finished_at IS NULL) OR (outcome <> 'RUNNING' AND finished_at IS NOT NULL)),
    CHECK (finished_at IS NULL OR finished_at >= started_at)
)@TABLE_OPTIONS@;

CREATE INDEX br_rejection_due_idx ON br_rejection(status, next_attempt_at, id);
CREATE INDEX br_rejection_lease_idx ON br_rejection(status, lease_expires_at, id);
