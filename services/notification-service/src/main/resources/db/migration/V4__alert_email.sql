ALTER TABLE operational_alerts DROP CONSTRAINT operational_alerts_code_check;
ALTER TABLE operational_alerts ADD CONSTRAINT operational_alerts_code_check CHECK (code IN ('JOB_FAILED','JOB_RECOVERED'));
ALTER TABLE operational_alerts ADD COLUMN recovered_by UUID REFERENCES received_job_events(id);
ALTER TABLE operational_alerts ADD COLUMN related_alert_id UUID REFERENCES operational_alerts(event_id);
ALTER TABLE operational_alerts ADD COLUMN email_decision VARCHAR(32) NOT NULL DEFAULT 'DISABLED';

CREATE TABLE alert_email_settings (
    project_id UUID NOT NULL,
    environment_id UUID PRIMARY KEY,
    environment_kind VARCHAR(4) NOT NULL CHECK (environment_kind IN ('DEV','PROD')),
    enabled BOOLEAN NOT NULL,
    recipient VARCHAR(320) NOT NULL,
    suppression_minutes INTEGER NOT NULL CHECK (suppression_minutes BETWEEN 1 AND 1440),
    recovery_enabled BOOLEAN NOT NULL,
    revision BIGINT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    actor VARCHAR(200) NOT NULL,
    request_id VARCHAR(32) NOT NULL
);
CREATE TABLE alert_email_settings_audit (
    environment_id UUID NOT NULL,
    revision BIGINT NOT NULL,
    settings JSONB NOT NULL,
    PRIMARY KEY(environment_id,revision)
);
CREATE TABLE alert_email_deliveries (
    event_id UUID PRIMARY KEY REFERENCES operational_alerts(event_id),
    recipient VARCHAR(320) NOT NULL,
    settings_revision BIGINT NOT NULL,
    state VARCHAR(16) NOT NULL CHECK (state IN ('PENDING','SENDING','MOCK','ACCEPTED','FAILED','UNKNOWN','CANCELLED','BLOCKED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    provider_id VARCHAR(128)
);
CREATE INDEX alert_email_pending ON alert_email_deliveries(created_at) WHERE state='PENDING';
CREATE INDEX received_events_order ON received_job_events(project_id,environment_id,
    ((envelope->>'targetRevision')::bigint) DESC,occurred_at DESC);

-- Existing successes can explain recovery, but migrations never queue historical emails.
UPDATE operational_alerts a SET recovered_by=(
    SELECT s.id FROM received_job_events s JOIN received_job_events f ON f.id=a.event_id
    WHERE s.project_id=a.project_id AND s.environment_id=a.environment_id AND s.event_type='job.succeeded'
      AND ((s.envelope->>'targetRevision')::bigint,s.occurred_at) > ((f.envelope->>'targetRevision')::bigint,f.occurred_at)
    ORDER BY (s.envelope->>'targetRevision')::bigint,s.occurred_at,s.id LIMIT 1
);
