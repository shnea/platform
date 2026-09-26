ALTER TABLE project_outbox DROP CONSTRAINT project_outbox_job_id_event_type_key;
CREATE UNIQUE INDEX outbox_job_final ON project_outbox(job_id,event_type)
    WHERE event_type IN ('job.succeeded','job.failed','job.cancelled');
ALTER TABLE project_outbox ADD COLUMN causation_id UUID REFERENCES project_outbox(id);
CREATE UNIQUE INDEX outbox_backlog_close ON project_outbox(causation_id) WHERE causation_id IS NOT NULL;

CREATE TABLE job_backlog_settings (
    environment_id UUID PRIMARY KEY REFERENCES environments(id),
    enabled BOOLEAN NOT NULL DEFAULT false,
    threshold_seconds INTEGER NOT NULL DEFAULT 300 CHECK (threshold_seconds BETWEEN 60 AND 86400),
    consecutive_checks INTEGER NOT NULL DEFAULT 2 CHECK (consecutive_checks BETWEEN 1 AND 10),
    revision BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ, actor VARCHAR(200), request_id VARCHAR(32),
    next_check_at TIMESTAMPTZ NOT NULL DEFAULT now(), last_checked_at TIMESTAMPTZ,
    observed_job_id UUID REFERENCES platform_jobs(id), observed_since TIMESTAMPTZ,
    breach_checks INTEGER NOT NULL DEFAULT 0, clear_checks INTEGER NOT NULL DEFAULT 0,
    active_job_id UUID REFERENCES platform_jobs(id), active_event_id UUID REFERENCES project_outbox(id)
);
CREATE INDEX backlog_due ON job_backlog_settings(next_check_at) WHERE enabled;
CREATE TABLE job_backlog_settings_audit (
    environment_id UUID NOT NULL REFERENCES environments(id), revision BIGINT NOT NULL,
    enabled BOOLEAN NOT NULL, threshold_seconds INTEGER NOT NULL, consecutive_checks INTEGER NOT NULL,
    actor VARCHAR(200) NOT NULL, request_id VARCHAR(32) NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY(environment_id,revision)
);
