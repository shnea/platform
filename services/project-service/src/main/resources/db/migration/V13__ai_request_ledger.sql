-- Only request identity and fingerprints are retained; no prompt, input, vector or result body.
CREATE TABLE ai_request_ledger (
    environment_id uuid NOT NULL REFERENCES environments(id),
    request_id varchar(128) NOT NULL,
    fingerprint char(64) NOT NULL,
    remote_id uuid,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (environment_id, request_id)
);
