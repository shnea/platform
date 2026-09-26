CREATE TABLE external_jobs (
 id uuid PRIMARY KEY, project_id uuid NOT NULL REFERENCES projects(id),
 environment_id uuid NOT NULL REFERENCES environments(id), request_key uuid NOT NULL,
 queue varchar(64) NOT NULL, payload jsonb NOT NULL, state varchar(20) NOT NULL,
 attempts integer NOT NULL DEFAULT 0, max_attempts integer NOT NULL,
 scheduled_at timestamptz, next_run_at timestamptz NOT NULL DEFAULT now(),
 worker_id varchar(100), lease_hash varchar(64), lease_until timestamptz,
 progress integer NOT NULL DEFAULT 0, result jsonb, error_code varchar(80), report_retryable boolean,
 retry_of uuid UNIQUE REFERENCES external_jobs(id) ON DELETE SET NULL,
 request_id varchar(32), created_at timestamptz NOT NULL DEFAULT now(),
 updated_at timestamptz NOT NULL DEFAULT now(), completed_at timestamptz,
 UNIQUE(environment_id,request_key),
 CHECK(state IN ('QUEUED','RUNNING','RETRY_WAIT','SUCCEEDED','FAILED','CANCELLED')),
 CHECK(max_attempts BETWEEN 1 AND 10), CHECK(progress BETWEEN 0 AND 100)
);
CREATE INDEX external_jobs_claim ON external_jobs(environment_id,queue,next_run_at,created_at) WHERE state IN ('QUEUED','RETRY_WAIT');
CREATE INDEX external_jobs_list ON external_jobs(environment_id,created_at DESC,id);
CREATE INDEX external_jobs_expiry ON external_jobs(lease_until) WHERE state='RUNNING';
CREATE TABLE external_job_attempts (
 job_id uuid NOT NULL REFERENCES external_jobs(id) ON DELETE CASCADE, attempt integer NOT NULL,
 worker_id varchar(100) NOT NULL, state varchar(20) NOT NULL, error_code varchar(80),
 started_at timestamptz NOT NULL DEFAULT now(), ended_at timestamptz,
 PRIMARY KEY(job_id,attempt)
);
-- Quota metadata only. Log contents and indexes belong to Loki.
CREATE TABLE log_ingestion_budgets (
 environment_id uuid NOT NULL REFERENCES environments(id), day date NOT NULL,
 reserved_bytes bigint NOT NULL DEFAULT 0, requests integer NOT NULL DEFAULT 0,
 minute_bucket timestamptz NOT NULL DEFAULT date_trunc('minute',now()), minute_requests integer NOT NULL DEFAULT 1,
 PRIMARY KEY(environment_id,day)
);
