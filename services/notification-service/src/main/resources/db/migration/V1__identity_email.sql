CREATE TABLE identity_email (
    id UUID PRIMARY KEY,
    environment_id UUID NOT NULL,
    fingerprint VARCHAR(64) NOT NULL,
    delivery VARCHAR(12) NOT NULL CHECK (delivery IN ('MOCK','NCP')),
    state VARCHAR(16) NOT NULL CHECK (state IN ('SENDING','MOCK','ACCEPTED','FAILED','UNKNOWN')),
    provider_id VARCHAR(128),
    recipient VARCHAR(320),
    subject VARCHAR(998),
    text_body TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX identity_email_environment ON identity_email(environment_id, created_at DESC);
