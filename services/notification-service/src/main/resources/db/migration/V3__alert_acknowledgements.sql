-- First acknowledgement is the immutable audit record. It does not resolve the underlying Job.
CREATE TABLE alert_acknowledgements (
    event_id UUID PRIMARY KEY REFERENCES operational_alerts(event_id),
    actor VARCHAR(200) NOT NULL,
    request_id VARCHAR(32) NOT NULL,
    acknowledged_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX operational_alerts_scope ON operational_alerts(project_id,environment_id,created_at DESC,event_id);
