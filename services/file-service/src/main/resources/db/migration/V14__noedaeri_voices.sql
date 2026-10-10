ALTER TABLE file_noedaeri_tasks ADD COLUMN voice_id uuid;
ALTER TABLE file_noedaeri_tasks ADD COLUMN creation_started_at timestamptz;
UPDATE file_noedaeri_tasks SET creation_started_at=created_at WHERE state='NEW' AND kind='tts.synthesize';
CREATE INDEX file_noedaeri_tasks_voice ON file_noedaeri_tasks(voice_id) WHERE voice_id IS NOT NULL;
CREATE TABLE file_noedaeri_voice_samples (
    environment_id uuid NOT NULL,
    project_id uuid NOT NULL,
    actor_id uuid NOT NULL,
    voice_id uuid NOT NULL,
    file_id uuid NOT NULL REFERENCES files(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (environment_id,actor_id,voice_id,file_id)
);
CREATE TABLE file_noedaeri_voice_audit (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    environment_id uuid NOT NULL,
    actor varchar(80) NOT NULL,
    voice_id uuid NOT NULL,
    action varchar(60) NOT NULL,
    request_id varchar(40),
    created_at timestamptz NOT NULL DEFAULT now()
);
