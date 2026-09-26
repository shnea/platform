CREATE INDEX jobs_environment_completed ON platform_jobs(environment_id,completed_at)
    WHERE completed_at IS NOT NULL;
