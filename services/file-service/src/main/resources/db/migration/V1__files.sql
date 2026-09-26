CREATE TABLE file_retention_policies (
    environment_id uuid NOT NULL,
    code varchar(60) NOT NULL,
    unused_days integer CHECK (unused_days > 0),
    PRIMARY KEY (environment_id,code)
);
CREATE TABLE files (
    id uuid PRIMARY KEY,
    project_id uuid NOT NULL,
    environment_id uuid NOT NULL,
    owner_credential_id uuid NOT NULL,
    request_id uuid NOT NULL,
    original_name varchar(255) NOT NULL,
    size_bytes bigint NOT NULL CHECK (size_bytes BETWEEN 0 AND 5000000000),
    expected_sha256 char(64) NOT NULL,
    received_bytes bigint NOT NULL DEFAULT 0 CHECK (received_bytes >= 0 AND received_bytes <= size_bytes),
    state varchar(16) NOT NULL DEFAULT 'UPLOADING' CHECK (state IN ('UPLOADING','READY','CANCELLED','EXPIRED','DELETED')),
    visibility varchar(10) NOT NULL CHECK (visibility IN ('PUBLIC','PRIVATE')),
    upload_visibility varchar(10) NOT NULL CHECK (upload_visibility IN ('PUBLIC','PRIVATE')),
    retention_code varchar(60) NOT NULL DEFAULT 'default',
    created_at timestamptz NOT NULL DEFAULT now(),
    upload_expires_at timestamptz NOT NULL,
    completed_at timestamptz,
    last_used_at timestamptz,
    purged_at timestamptz,
    UNIQUE (environment_id,owner_credential_id,request_id),
    FOREIGN KEY (environment_id,retention_code) REFERENCES file_retention_policies(environment_id,code)
);
CREATE INDEX files_environment ON files(environment_id,created_at DESC,id);
CREATE INDEX files_cleanup ON files(state,upload_expires_at) WHERE purged_at IS NULL;
CREATE TABLE file_audit (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    file_id uuid NOT NULL REFERENCES files(id),
    environment_id uuid NOT NULL,
    actor varchar(80) NOT NULL,
    action varchar(60) NOT NULL,
    request_id varchar(40),
    created_at timestamptz NOT NULL DEFAULT now()
);
