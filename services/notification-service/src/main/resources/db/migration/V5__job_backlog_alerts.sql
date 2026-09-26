ALTER TABLE received_job_events DROP CONSTRAINT received_job_events_target_id_key;
CREATE UNIQUE INDEX received_job_final ON received_job_events(target_id)
    WHERE event_type IN ('job.succeeded','job.failed','job.cancelled');
CREATE UNIQUE INDEX received_backlog_close ON received_job_events((envelope->>'causationId'))
    WHERE event_type IN ('job.backlog_recovered','job.backlog_closed');
ALTER TABLE operational_alerts DROP CONSTRAINT operational_alerts_code_check;
ALTER TABLE operational_alerts ADD CONSTRAINT operational_alerts_code_check
    CHECK (code IN ('JOB_FAILED','JOB_RECOVERED','JOB_BACKLOGGED','JOB_BACKLOG_RECOVERED','JOB_BACKLOG_CLOSED'));
