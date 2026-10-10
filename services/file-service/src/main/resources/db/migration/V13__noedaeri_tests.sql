ALTER TABLE files ADD COLUMN automatic_derivatives boolean NOT NULL DEFAULT true;

CREATE TABLE file_noedaeri_tasks (
    id uuid PRIMARY KEY,
    environment_id uuid NOT NULL,
    project_id uuid NOT NULL,
    actor_id uuid NOT NULL,
    request_id uuid NOT NULL,
    source_file_id uuid REFERENCES files(id),
    kind varchar(32) NOT NULL,
    fingerprint char(64) NOT NULL,
    payload jsonb,
    job_id uuid UNIQUE,
    event_id uuid,
    state varchar(16) NOT NULL DEFAULT 'NEW' CHECK (state IN ('NEW','ACTIVE','IMPORTED','FAILED','CANCELLED','DISCARDED')),
    remote_status varchar(32),
    stage varchar(80),
    cancel_requested boolean NOT NULL DEFAULT false,
    manifest jsonb,
    artifacts jsonb NOT NULL DEFAULT '{}',
    error_code varchar(100),
    attempts integer NOT NULL DEFAULT 0,
    next_check_at timestamptz NOT NULL DEFAULT now(),
    receipt_at timestamptz,
    remote_expires_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (environment_id,actor_id,request_id)
);
CREATE INDEX file_noedaeri_tasks_due ON file_noedaeri_tasks(next_check_at) WHERE state IN ('NEW','ACTIVE','IMPORTED');
CREATE TABLE file_noedaeri_task_events (
    event_id uuid PRIMARY KEY,
    task_id uuid NOT NULL REFERENCES file_noedaeri_tasks(id),
    job_id uuid NOT NULL,
    body_hash char(64) NOT NULL,
    received_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE file_noedaeri_task_audit (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    task_id uuid NOT NULL REFERENCES file_noedaeri_tasks(id),
    environment_id uuid NOT NULL,
    actor varchar(80) NOT NULL,
    action varchar(60) NOT NULL,
    request_id varchar(40),
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE file_noedaeri_artifacts (
    file_id uuid PRIMARY KEY REFERENCES files(id),
    task_id uuid NOT NULL REFERENCES file_noedaeri_tasks(id),
    source_file_id uuid REFERENCES files(id)
);
CREATE INDEX file_noedaeri_artifacts_source ON file_noedaeri_artifacts(source_file_id);
