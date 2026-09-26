ALTER TABLE files ADD COLUMN owner_kind varchar(16) NOT NULL DEFAULT 'CREDENTIAL' CHECK (owner_kind IN ('CREDENTIAL','ADMIN'));
ALTER TABLE files DROP CONSTRAINT files_environment_id_owner_credential_id_request_id_key;
ALTER TABLE files ADD CONSTRAINT files_upload_request_unique UNIQUE (environment_id,owner_kind,owner_credential_id,request_id);
CREATE TABLE file_download_tickets (
    token_hash char(64) PRIMARY KEY,
    file_id uuid NOT NULL REFERENCES files(id),
    expires_at timestamptz NOT NULL
);
CREATE INDEX file_download_expiry ON file_download_tickets(expires_at);
CREATE INDEX file_download_file ON file_download_tickets(file_id);
