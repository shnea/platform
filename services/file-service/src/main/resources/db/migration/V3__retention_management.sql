ALTER TABLE file_retention_policies
    ADD COLUMN display_name varchar(120),
    ADD COLUMN period_value integer,
    ADD COLUMN period_unit varchar(8),
    ADD COLUMN enabled boolean NOT NULL DEFAULT true,
    ADD COLUMN revision bigint NOT NULL DEFAULT 1;
UPDATE file_retention_policies SET display_name=code,
    period_value=CASE WHEN code='default' THEN 1 ELSE unused_days END,
    period_unit=CASE WHEN unused_days IS NULL THEN 'FOREVER' WHEN code='default' THEN 'YEAR' ELSE 'DAY' END;
ALTER TABLE file_retention_policies ALTER COLUMN display_name SET NOT NULL, ALTER COLUMN period_unit SET NOT NULL;
ALTER TABLE file_retention_policies DROP COLUMN unused_days;
ALTER TABLE file_retention_policies ADD CONSTRAINT retention_period CHECK (
    (period_unit='FOREVER' AND period_value IS NULL AND code='영구') OR
    (period_unit='DAY' AND period_value IS NOT NULL AND period_value BETWEEN 1 AND 36500) OR
    (period_unit='MONTH' AND period_value IS NOT NULL AND period_value BETWEEN 1 AND 1200) OR
    (period_unit='YEAR' AND period_value IS NOT NULL AND period_value BETWEEN 1 AND 100));
CREATE TABLE file_retention_settings (
    environment_id uuid PRIMARY KEY,
    enabled boolean NOT NULL DEFAULT false,
    grace_days integer NOT NULL DEFAULT 7 CHECK(grace_days BETWEEN 1 AND 30),
    revision bigint NOT NULL DEFAULT 1,
    last_checked_at timestamptz,
    error_code varchar(60)
);
ALTER TABLE files ADD COLUMN retention_marked_at timestamptz;
ALTER TABLE files ADD COLUMN upload_retention_code varchar(60);
UPDATE files SET upload_retention_code=retention_code;
ALTER TABLE files ALTER COLUMN upload_retention_code SET NOT NULL;
CREATE TABLE file_retention_audit (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    environment_id uuid NOT NULL,
    actor varchar(80) NOT NULL,
    action varchar(60) NOT NULL,
    policy_code varchar(60),
    before_value jsonb,
    after_value jsonb NOT NULL,
    affected_files bigint NOT NULL,
    request_id varchar(40),
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX retention_audit_environment ON file_retention_audit(environment_id,id DESC);
CREATE TABLE file_download_leases (
    id uuid PRIMARY KEY,
    file_id uuid NOT NULL REFERENCES files(id),
    expires_at timestamptz NOT NULL
);
CREATE INDEX download_leases_file ON file_download_leases(file_id,expires_at);
CREATE FUNCTION file_retention_due(base timestamptz, amount integer, unit text) RETURNS timestamptz
LANGUAGE sql IMMUTABLE STRICT AS $$
    SELECT ((base AT TIME ZONE 'UTC') + CASE unit
        WHEN 'DAY' THEN make_interval(days=>amount)
        WHEN 'MONTH' THEN make_interval(months=>amount)
        WHEN 'YEAR' THEN make_interval(years=>amount) END) AT TIME ZONE 'UTC'
$$;
CREATE INDEX files_retention_ready ON files(environment_id,retention_code,last_used_at) WHERE state='READY';
