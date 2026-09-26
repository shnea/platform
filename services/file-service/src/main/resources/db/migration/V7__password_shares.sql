CREATE TABLE file_shares (
    id uuid PRIMARY KEY,
    file_id uuid NOT NULL REFERENCES files(id),
    access_revision bigint NOT NULL,
    password_hash varchar(128) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    expires_at timestamptz NOT NULL,
    revoked_at timestamptz,
    attempts integer NOT NULL DEFAULT 0,
    window_started_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX file_shares_file ON file_shares(file_id, created_at DESC);
ALTER TABLE file_view_tokens ADD COLUMN share_id uuid REFERENCES file_shares(id);
CREATE INDEX file_view_tokens_share ON file_view_tokens(share_id) WHERE share_id IS NOT NULL;
ALTER TABLE file_audit ADD COLUMN share_id uuid REFERENCES file_shares(id);
