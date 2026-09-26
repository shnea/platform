ALTER TABLE files ADD COLUMN access_revision bigint NOT NULL DEFAULT 1;
CREATE TABLE file_views (
    file_id uuid PRIMARY KEY REFERENCES files(id),
    state varchar(16) NOT NULL DEFAULT 'QUEUED' CHECK(state IN ('QUEUED','PROCESSING','READY','UNSUPPORTED','FAILED')),
    kind varchar(16) NOT NULL DEFAULT 'OTHER',
    media_type varchar(80) NOT NULL DEFAULT 'application/octet-stream',
    thumbnail boolean NOT NULL DEFAULT false,
    attempts integer NOT NULL DEFAULT 0,
    started_at timestamptz,
    finished_at timestamptz,
    error_code varchar(60)
);
CREATE TABLE file_view_tokens (
    token_hash char(64) PRIMARY KEY,
    file_id uuid NOT NULL REFERENCES files(id),
    access_revision bigint NOT NULL,
    expires_at timestamptz NOT NULL
);
CREATE INDEX file_view_tokens_file ON file_view_tokens(file_id);
CREATE INDEX file_view_tokens_expiry ON file_view_tokens(expires_at);
