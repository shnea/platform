ALTER TABLE files ADD COLUMN video_bytes bigint NOT NULL DEFAULT 0 CHECK(video_bytes>=0);
ALTER TABLE files ADD COLUMN video_reserved_bytes bigint NOT NULL DEFAULT 0 CHECK(video_reserved_bytes>=0);
ALTER TABLE file_view_tokens ADD COLUMN playback_expires_at timestamptz;
CREATE TABLE file_videos (
    file_id uuid PRIMARY KEY REFERENCES files(id),
    state varchar(16) NOT NULL DEFAULT 'QUEUED' CHECK(state IN ('QUEUED','PROCESSING','READY','UNSUPPORTED','FAILED')),
    generation uuid,
    attempts integer NOT NULL DEFAULT 0,
    progress integer NOT NULL DEFAULT 0 CHECK(progress BETWEEN 0 AND 100),
    duration_seconds double precision,
    variants jsonb NOT NULL DEFAULT '[]',
    error_code varchar(60),
    started_at timestamptz,
    heartbeat_at timestamptz,
    finished_at timestamptz
);
CREATE INDEX file_videos_queue ON file_videos(state,heartbeat_at);
