ALTER TABLE file_views ADD COLUMN media_generation uuid;
ALTER TABLE file_views ADD COLUMN processing_generation uuid;
ALTER TABLE file_views ADD COLUMN processing_backend varchar(16) NOT NULL DEFAULT 'local';
ALTER TABLE file_videos ADD COLUMN processing_backend varchar(16) NOT NULL DEFAULT 'local';

CREATE TABLE file_media_jobs (
    request_id uuid PRIMARY KEY,
    file_id uuid NOT NULL REFERENCES files(id),
    kind varchar(32) NOT NULL CHECK (kind IN ('image.package','video.package')),
    generation uuid NOT NULL,
    job_id uuid UNIQUE,
    state varchar(16) NOT NULL DEFAULT 'NEW' CHECK (state IN ('NEW','ACTIVE','IMPORTED','FAILED','DISCARDED')),
    event_id uuid,
    attempts integer NOT NULL DEFAULT 0,
    error_code varchar(60),
    next_check_at timestamptz NOT NULL DEFAULT now(),
    receipt_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE(file_id,kind,generation)
);
CREATE INDEX file_media_pending ON file_media_jobs(next_check_at) WHERE state IN ('NEW','ACTIVE','IMPORTED');
CREATE TABLE file_media_inbox (
    event_id uuid PRIMARY KEY,
    job_id uuid NOT NULL,
    request_id uuid NOT NULL REFERENCES file_media_jobs(request_id),
    payload jsonb NOT NULL,
    body_hash char(64) NOT NULL,
    received_at timestamptz NOT NULL DEFAULT now(),
    processed_at timestamptz
);
