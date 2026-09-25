CREATE TABLE projects (
    id uuid PRIMARY KEY,
    code varchar(40) NOT NULL UNIQUE,
    name varchar(120) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE environments (
    id uuid PRIMARY KEY,
    project_id uuid NOT NULL REFERENCES projects(id),
    code varchar(40) NOT NULL,
    kind varchar(4) NOT NULL CHECK (kind IN ('DEV', 'PROD')),
    realm varchar(100) NOT NULL UNIQUE,
    registration_allowed boolean NOT NULL,
    redirect_uris jsonb NOT NULL,
    state varchar(10) NOT NULL CHECK (state IN ('PENDING','READY','FAILED')),
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE(project_id, code)
);
CREATE TABLE service_credentials (
    id uuid PRIMARY KEY,
    environment_id uuid NOT NULL REFERENCES environments(id),
    secret_hash varchar(64) NOT NULL,
    expires_at timestamptz NOT NULL,
    revoked_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX credentials_environment_idx ON service_credentials(environment_id);
CREATE TABLE audit_events (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    actor varchar(100) NOT NULL,
    action varchar(60) NOT NULL,
    target_id uuid NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);
