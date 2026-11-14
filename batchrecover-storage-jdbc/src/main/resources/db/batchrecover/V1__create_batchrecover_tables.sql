-- PostgreSQL 16. Apply explicitly with a migration tool and a dedicated history table.
CREATE TABLE br_rejection (
    id uuid PRIMARY KEY,
    application_scope varchar(128) NOT NULL,
    job_instance_id bigint NOT NULL CHECK (job_instance_id >= 0),
    job_execution_id bigint NOT NULL CHECK (job_execution_id >= 0),
    step_execution_id bigint NOT NULL CHECK (step_execution_id >= 0),
    step_name varchar(128) NOT NULL,
    source_occurrence_id varchar(512) NOT NULL,
    phase varchar(16) NOT NULL CHECK (phase = 'PROCESS'),
    handler_id varchar(128) NOT NULL,
    error_code varchar(128) NOT NULL,
    category varchar(16) NOT NULL CHECK (category IN ('BUSINESS', 'TECHNICAL', 'UNKNOWN')),
    payload_type varchar(128) NOT NULL,
    status varchar(16) NOT NULL CHECK (status IN ('PENDING','QUEUED','RUNNING','RECOVERED','DEAD','DISCARDED')),
    current_revision integer NOT NULL CHECK (current_revision >= 0),
    attempt_count integer NOT NULL CHECK (attempt_count >= 0),
    max_attempts integer NOT NULL CHECK (max_attempts BETWEEN 1 AND 100),
    next_attempt_at timestamptz,
    lease_token uuid,
    lease_expires_at timestamptz,
    version bigint NOT NULL CHECK (version >= 0),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL CHECK (updated_at >= created_at),
    CONSTRAINT br_rejection_identity UNIQUE (application_scope, job_instance_id, step_name, source_occurrence_id, phase),
    CHECK (attempt_count <= max_attempts),
    CHECK (status NOT IN ('PENDING','QUEUED') OR attempt_count < max_attempts),
    CHECK ((status = 'QUEUED') = (next_attempt_at IS NOT NULL)),
    CHECK ((status = 'RUNNING') = (lease_token IS NOT NULL)),
    CHECK ((status = 'RUNNING') = (lease_expires_at IS NOT NULL))
);

CREATE TABLE br_payload_revision (
    rejection_id uuid NOT NULL REFERENCES br_rejection(id),
    revision integer NOT NULL CHECK (revision >= 0),
    payload_type varchar(128) NOT NULL,
    schema_version integer NOT NULL CHECK (schema_version > 0),
    encoded_payload text NOT NULL CHECK (octet_length(encoded_payload) BETWEEN 1 AND 65536),
    actor varchar(128) NOT NULL,
    reason varchar(512) NOT NULL,
    created_at timestamptz NOT NULL,
    PRIMARY KEY (rejection_id, revision)
);

ALTER TABLE br_rejection ADD CONSTRAINT br_current_revision_fk
    FOREIGN KEY (id, current_revision) REFERENCES br_payload_revision(rejection_id, revision)
    DEFERRABLE INITIALLY DEFERRED;

CREATE TABLE br_audit_event (
    id uuid PRIMARY KEY,
    rejection_id uuid NOT NULL REFERENCES br_rejection(id),
    version bigint NOT NULL CHECK (version >= 0),
    action varchar(32) NOT NULL CHECK (action IN ('CAPTURED','CORRECTED','QUEUED','DISCARDED',
        'CLAIMED','RECOVERED','RETRY_SCHEDULED','DEAD','VALIDATION_FAILED')),
    actor varchar(128) NOT NULL,
    reason varchar(512) NOT NULL,
    previous_status varchar(16) CHECK (previous_status IN ('PENDING','QUEUED','RUNNING','RECOVERED','DEAD','DISCARDED')),
    new_status varchar(16) NOT NULL CHECK (new_status IN ('PENDING','QUEUED','RUNNING','RECOVERED','DEAD','DISCARDED')),
    created_at timestamptz NOT NULL,
    UNIQUE (rejection_id, version)
);

CREATE TABLE br_recovery_attempt (
    id uuid PRIMARY KEY,
    rejection_id uuid NOT NULL,
    revision integer NOT NULL,
    attempt_number integer NOT NULL CHECK (attempt_number > 0),
    lease_token uuid NOT NULL UNIQUE,
    started_at timestamptz NOT NULL,
    finished_at timestamptz,
    outcome varchar(32) NOT NULL CHECK (outcome IN ('RUNNING','RECOVERED','RETRYABLE_FAILURE','VALIDATION_FAILURE','EXPIRED','DEAD')),
    error_code varchar(128),
    FOREIGN KEY (rejection_id, revision) REFERENCES br_payload_revision(rejection_id, revision),
    UNIQUE (rejection_id, attempt_number),
    CHECK ((outcome = 'RUNNING') = (finished_at IS NULL)),
    CHECK (finished_at IS NULL OR finished_at >= started_at)
);

CREATE INDEX br_rejection_due_idx ON br_rejection(next_attempt_at, id) WHERE status = 'QUEUED';
CREATE INDEX br_rejection_lease_idx ON br_rejection(lease_expires_at, id) WHERE status = 'RUNNING';
