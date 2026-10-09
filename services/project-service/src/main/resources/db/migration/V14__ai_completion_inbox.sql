ALTER TABLE ai_request_ledger ADD COLUMN task_type varchar(64);
ALTER TABLE ai_request_ledger ADD COLUMN notify boolean NOT NULL DEFAULT false;

CREATE TABLE ai_completion_inbox (
    event_id uuid PRIMARY KEY,
    job_id uuid NOT NULL UNIQUE,
    environment_id uuid NOT NULL,
    request_id varchar(128) NOT NULL,
    task_type varchar(64) NOT NULL,
    status varchar(16) NOT NULL CHECK (status IN ('succeeded','failed','cancelled')),
    body_hash char(64) NOT NULL,
    occurred_at timestamptz NOT NULL,
    expires_at timestamptz,
    error_code varchar(80),
    created_at timestamptz NOT NULL DEFAULT now(),
    received_at timestamptz,
    FOREIGN KEY (environment_id,request_id) REFERENCES ai_request_ledger(environment_id,request_id)
);
CREATE INDEX ai_completion_environment_created ON ai_completion_inbox(environment_id,created_at DESC,event_id);
