-- Immutable events: arrival order never overwrites current project/environment state.
CREATE TABLE received_job_events (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL,
    environment_id UUID NOT NULL,
    target_id UUID NOT NULL UNIQUE,
    event_type VARCHAR(64) NOT NULL,
    envelope JSONB NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    received_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX received_events_scope ON received_job_events(project_id,environment_id,occurred_at DESC);
CREATE TABLE operational_alerts (
    event_id UUID PRIMARY KEY REFERENCES received_job_events(id),
    project_id UUID NOT NULL,
    environment_id UUID NOT NULL,
    code VARCHAR(64) NOT NULL CHECK (code='JOB_FAILED'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
