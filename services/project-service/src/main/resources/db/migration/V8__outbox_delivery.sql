ALTER TABLE project_outbox ADD COLUMN target_revision BIGINT;
UPDATE project_outbox o SET target_revision=j.target_revision FROM platform_jobs j WHERE j.id=o.job_id;
ALTER TABLE project_outbox ALTER COLUMN target_revision SET NOT NULL;
ALTER TABLE project_outbox ADD COLUMN cycle_attempts INTEGER NOT NULL DEFAULT 0;
ALTER TABLE project_outbox ADD COLUMN lease_token UUID;
ALTER TABLE project_outbox ADD COLUMN lease_until TIMESTAMPTZ;
ALTER TABLE project_outbox ADD COLUMN error_code VARCHAR(64);
CREATE TABLE outbox_attempts (
    event_id UUID NOT NULL REFERENCES project_outbox(id),
    attempt INTEGER NOT NULL,
    state VARCHAR(16) NOT NULL CHECK (state IN ('RUNNING','DELIVERED','FAILED','ABANDONED')),
    error_code VARCHAR(64),
    http_status INTEGER,
    started_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    ended_at TIMESTAMPTZ,
    PRIMARY KEY(event_id,attempt)
);
