CREATE TABLE platform_jobs (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL REFERENCES projects(id),
    environment_id UUID NOT NULL REFERENCES environments(id),
    type VARCHAR(40) NOT NULL CHECK (type = 'ENVIRONMENT_PROVISION'),
    state VARCHAR(16) NOT NULL CHECK (state IN ('QUEUED','RUNNING','RETRY_WAIT','SUCCEEDED','FAILED','CANCELLED')),
    target_revision BIGINT NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0,
    max_attempts INTEGER NOT NULL DEFAULT 3,
    next_run_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    lease_until TIMESTAMPTZ,
    lease_token UUID,
    request_id VARCHAR(32) NOT NULL,
    error_code VARCHAR(64),
    retry_of UUID UNIQUE REFERENCES platform_jobs(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at TIMESTAMPTZ
);
CREATE UNIQUE INDEX jobs_one_active_environment ON platform_jobs(environment_id)
    WHERE state IN ('QUEUED','RUNNING','RETRY_WAIT');
CREATE INDEX jobs_due ON platform_jobs(next_run_at) WHERE state IN ('QUEUED','RETRY_WAIT');
CREATE INDEX jobs_expired ON platform_jobs(lease_until) WHERE state = 'RUNNING';
CREATE INDEX jobs_environment_history ON platform_jobs(environment_id, created_at DESC);

CREATE TABLE job_attempts (
    job_id UUID NOT NULL REFERENCES platform_jobs(id),
    attempt INTEGER NOT NULL,
    state VARCHAR(16) NOT NULL CHECK (state IN ('RUNNING','SUCCEEDED','FAILED','ABANDONED','CANCELLED')),
    error_code VARCHAR(64),
    started_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    ended_at TIMESTAMPTZ,
    PRIMARY KEY (job_id, attempt)
);

-- Business completion and event creation commit together. Delivery is a separate worker responsibility.
CREATE TABLE project_outbox (
    id UUID PRIMARY KEY,
    event_type VARCHAR(64) NOT NULL,
    schema_version INTEGER NOT NULL DEFAULT 1,
    source VARCHAR(40) NOT NULL DEFAULT 'project-service',
    project_id UUID NOT NULL,
    environment_id UUID NOT NULL,
    job_id UUID NOT NULL REFERENCES platform_jobs(id),
    request_id VARCHAR(32) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    payload JSONB NOT NULL,
    state VARCHAR(16) NOT NULL DEFAULT 'PENDING' CHECK (state IN ('PENDING','DELIVERED','FAILED')),
    attempts INTEGER NOT NULL DEFAULT 0,
    next_run_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    delivered_at TIMESTAMPTZ,
    UNIQUE (job_id, event_type)
);
CREATE INDEX outbox_pending ON project_outbox(next_run_at) WHERE state = 'PENDING';
