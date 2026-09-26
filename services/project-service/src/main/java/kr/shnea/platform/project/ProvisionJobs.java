package kr.shnea.platform.project;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
class ProvisionJobs {
    record Job(UUID id, UUID projectId, UUID environmentId, String type, String state, long targetRevision,
               int attempts, int maxAttempts, Instant nextRunAt, Instant leaseUntil, String requestId,
               String errorCode, UUID retryOf, Instant createdAt, Instant updatedAt, Instant completedAt) {}
    record Attempt(int attempt, String state, String errorCode, Instant startedAt, Instant endedAt) {}
    record Detail(Job job, List<Attempt> attempts) {}
    record Metrics(UUID projectId,UUID environmentId,Instant measuredAt,Instant windowFrom,
                   long queued,long retryWaiting,long running,long dueWaiting,Instant oldestWaitingAt,
                   Long oldestWaitingSeconds,long succeededLast24Hours,long failedLast24Hours,long cancelledLast24Hours) {}
    record Claim(Job job, UUID token) {}
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final ProjectService projects;

    ProvisionJobs(JdbcTemplate db, TransactionTemplate tx, ProjectService projects) {
        this.db = db; this.tx = tx; this.projects = projects;
    }

    Job enqueue(UUID environmentId, String actor, String requestId) {
        return tx.execute(status -> enqueue(environmentId, actor, requestId, null));
    }
    private Job enqueue(UUID environmentId, String actor, String requestId, UUID retryOf) {
        var envs = db.queryForList("SELECT project_id,revision FROM environments WHERE id=?", environmentId);
        if (envs.isEmpty()) throw ApiCode.RESOURCE_NOT_FOUND.failure();
        var env = envs.getFirst();
        UUID id = UUID.randomUUID();
        int inserted = db.update("""
            INSERT INTO platform_jobs(id,project_id,environment_id,type,state,target_revision,request_id,retry_of)
            VALUES (?,?,?,'ENVIRONMENT_PROVISION','QUEUED',?,?,?) ON CONFLICT DO NOTHING
            """, id, env.get("project_id"), environmentId, env.get("revision"), requestId, retryOf);
        if (inserted == 1) { audit(actor, "job.queued", id, environmentId); return job(id); }
        if (retryOf != null) {
            var retry = db.query("SELECT * FROM platform_jobs WHERE retry_of=?", (rs, row) -> map(rs), retryOf);
            if (!retry.isEmpty()) return retry.getFirst();
        }
        var active = db.query("SELECT * FROM platform_jobs WHERE environment_id=? AND state IN ('QUEUED','RUNNING','RETRY_WAIT')",
            (rs, row) -> map(rs), environmentId);
        // A worker can finish between the insert conflict and the read. Let the caller re-read instead of guessing.
        if (active.isEmpty()) throw ApiCode.JOB_STATE_CHANGED.failure();
        return active.getFirst();
    }

    List<Job> list(UUID environmentId, String state, int limit, int offset, Instant createdFrom, Instant createdTo) {
        if (limit < 1 || limit > 100 || offset < 0 || offset > 1_000_000) throw ApiCode.INVALID_PAGINATION.failure();
        if (state != null && !List.of("QUEUED","RUNNING","RETRY_WAIT","SUCCEEDED","FAILED","CANCELLED").contains(state))
            throw ApiCode.INVALID_REQUEST.failure();
        var clauses = new java.util.ArrayList<String>();
        var values = new java.util.ArrayList<Object>();
        if (createdFrom != null && createdTo != null && !createdFrom.isBefore(createdTo))
            throw ApiCode.INVALID_REQUEST.failure();
        if (environmentId != null) { clauses.add("environment_id=?"); values.add(environmentId); }
        if (state != null) { clauses.add("state=?"); values.add(state); }
        if (createdFrom != null) { clauses.add("created_at>=?"); values.add(java.sql.Timestamp.from(createdFrom)); }
        if (createdTo != null) { clauses.add("created_at<?"); values.add(java.sql.Timestamp.from(createdTo)); }
        values.add(limit); values.add(offset);
        return db.query("SELECT * FROM platform_jobs" + (clauses.isEmpty() ? "" : " WHERE " + String.join(" AND ", clauses))
            + " ORDER BY created_at DESC,id LIMIT ? OFFSET ?", (rs, row) -> map(rs), values.toArray());
    }

    Job job(UUID id) {
        return db.query("SELECT * FROM platform_jobs WHERE id=?", (rs, row) -> map(rs), id)
            .stream().findFirst().orElseThrow(ApiCode.RESOURCE_NOT_FOUND::failure);
    }
    Metrics metrics(UUID environmentId) {
        // One statement/snapshot and the database clock keep counts and the rolling window consistent.
        var rows=db.query("""
            WITH measured AS (SELECT statement_timestamp() AS at)
            SELECT e.project_id,e.id,m.at,m.at-interval '24 hours' AS window_from,
              count(j.id) FILTER(WHERE j.state='QUEUED') AS queued,
              count(j.id) FILTER(WHERE j.state='RETRY_WAIT') AS retry_waiting,
              count(j.id) FILTER(WHERE j.state='RUNNING') AS running,
              count(j.id) FILTER(WHERE j.state IN ('QUEUED','RETRY_WAIT') AND j.next_run_at<=m.at) AS due_waiting,
              min(j.updated_at) FILTER(WHERE j.state IN ('QUEUED','RETRY_WAIT')) AS oldest_waiting_at,
              CASE WHEN count(j.id) FILTER(WHERE j.state IN ('QUEUED','RETRY_WAIT'))>0 THEN
                greatest(0,floor(extract(epoch FROM m.at-min(j.updated_at) FILTER(WHERE j.state IN ('QUEUED','RETRY_WAIT')))))::bigint
                END AS oldest_waiting_seconds,
              count(j.id) FILTER(WHERE j.state='SUCCEEDED') AS succeeded,
              count(j.id) FILTER(WHERE j.state='FAILED') AS failed,
              count(j.id) FILTER(WHERE j.state='CANCELLED') AS cancelled
            FROM environments e CROSS JOIN measured m LEFT JOIN platform_jobs j ON j.environment_id=e.id
              AND (j.state IN ('QUEUED','RETRY_WAIT','RUNNING') OR
                   (j.completed_at>=m.at-interval '24 hours' AND j.completed_at<m.at))
            WHERE e.id=? GROUP BY e.id,m.at
            """,(rs,n)->new Metrics(rs.getObject("project_id",UUID.class),rs.getObject("id",UUID.class),instant(rs,"at"),instant(rs,"window_from"),
                rs.getLong("queued"),rs.getLong("retry_waiting"),rs.getLong("running"),rs.getLong("due_waiting"),instant(rs,"oldest_waiting_at"),
                rs.getObject("oldest_waiting_seconds",Long.class),rs.getLong("succeeded"),rs.getLong("failed"),rs.getLong("cancelled")),environmentId);
        return rows.stream().findFirst().orElseThrow(ApiCode.RESOURCE_NOT_FOUND::failure);
    }
    Detail detail(UUID id) {
        return new Detail(job(id), db.query("SELECT * FROM job_attempts WHERE job_id=? ORDER BY attempt",
            (rs, row) -> new Attempt(rs.getInt("attempt"), rs.getString("state"), rs.getString("error_code"),
                instant(rs, "started_at"), instant(rs, "ended_at")), id));
    }
    Job cancel(UUID id, String actor) {
        return tx.execute(status -> {
            Job job = lock(id);
            if (job.state().equals("CANCELLED")) return job;
            if (!List.of("QUEUED","RETRY_WAIT").contains(job.state())) throw ApiCode.JOB_NOT_CANCELLABLE.failure();
            finish(job, "CANCELLED", null, actor);
            return job(id);
        });
    }
    Job retry(UUID id, String actor, String requestId) {
        return tx.execute(status -> {
            // Serializes repeated retry commands; the failed row and its history stay immutable.
            Job job = lock(id);
            if (!job.state().equals("FAILED")) throw ApiCode.JOB_NOT_RETRYABLE.failure();
            return enqueue(job.environmentId(), actor, requestId, id);
        });
    }
    private Job lock(UUID id) {
        return db.query("SELECT * FROM platform_jobs WHERE id=? FOR UPDATE", (rs, row) -> map(rs), id)
            .stream().findFirst().orElseThrow(ApiCode.RESOURCE_NOT_FOUND::failure);
    }

    Claim claim() {
        return tx.execute(status -> {
            var due = db.query("""
                SELECT * FROM platform_jobs WHERE (state IN ('QUEUED','RETRY_WAIT') AND next_run_at<=now())
                    OR (state='RUNNING' AND lease_until<now())
                ORDER BY next_run_at,id FOR UPDATE SKIP LOCKED LIMIT 1
                """, (rs, row) -> map(rs));
            if (due.isEmpty()) return null;
            Job job = due.getFirst();
            if (job.state().equals("RUNNING")) {
                db.update("UPDATE job_attempts SET state='ABANDONED',error_code='WORKER_INTERRUPTED',ended_at=now() WHERE job_id=? AND state='RUNNING'", job.id());
                audit("system:jobs", "job.recovered", job.id(), job.environmentId());
            }
            if (job.attempts() >= job.maxAttempts()) { finish(job, "FAILED", "RETRY_EXHAUSTED", "system:jobs"); return null; }
            UUID token = UUID.randomUUID();
            db.update("UPDATE platform_jobs SET state='RUNNING',attempts=attempts+1,lease_token=?,lease_until=now()+interval '60 seconds',updated_at=now(),error_code=NULL WHERE id=?", token, job.id());
            db.update("INSERT INTO job_attempts(job_id,attempt,state) VALUES (?,?,'RUNNING')", job.id(), job.attempts()+1);
            audit("system:jobs", "job.started", job.id(), job.environmentId());
            return new Claim(job(job.id()), token);
        });
    }

    void execute(Claim claim) {
        tx.executeWithoutResult(status -> {
            // Keep the claimed row locked during this bounded HTTP operation. Other workers skip it even after
            // the lease expires; a crash rolls back this transaction and leaves the committed claim recoverable.
            var owned = db.queryForList("SELECT id FROM platform_jobs WHERE id=? AND state='RUNNING' AND lease_token=? AND lease_until>now() FOR UPDATE",
                claim.job().id(), claim.token());
            if (owned.isEmpty()) return;
            // Same lock order as synchronous project operations: project then environment.
            db.queryForList("SELECT id FROM projects WHERE id=? FOR UPDATE", claim.job().projectId());
            long revision = db.queryForObject("SELECT revision FROM environments WHERE id=?", Long.class, claim.job().environmentId());
            if (revision != claim.job().targetRevision()) {
                endAttempt(claim.job(), "CANCELLED", "JOB_TARGET_CHANGED");
                finish(claim.job(), "CANCELLED", "JOB_TARGET_CHANGED", "system:jobs");
                return;
            }
            var env = projects.provision(claim.job().environmentId(), "system:jobs");
            if (env.state().equals("READY")) {
                endAttempt(claim.job(), "SUCCEEDED", null);
                finish(claim.job(), "SUCCEEDED", null, "system:jobs");
            } else failAttempt(claim.job(), "ENVIRONMENT_PROVISION_FAILED");
        });
    }

    void failed(Claim claim) {
        // After rollback, only the current claim may publish a failure. Never expose the exception content.
        tx.executeWithoutResult(status -> {
            var owned = db.queryForList("SELECT id FROM platform_jobs WHERE id=? AND state='RUNNING' AND lease_token=? FOR UPDATE",
                claim.job().id(), claim.token());
            if (!owned.isEmpty()) failAttempt(claim.job(), "JOB_EXECUTION_FAILED");
        });
    }
    private void failAttempt(Job job, String error) {
        endAttempt(job, "FAILED", error);
        if (job.attempts() >= job.maxAttempts()) finish(job, "FAILED", error, "system:jobs");
        else {
            db.update("UPDATE platform_jobs SET state='RETRY_WAIT',error_code=?,next_run_at=now()+(? * interval '1 second'),lease_token=NULL,lease_until=NULL,updated_at=now() WHERE id=?",
                error, job.attempts() == 1 ? 10 : 30, job.id());
            audit("system:jobs", "job.retry_wait", job.id(), job.environmentId());
        }
    }
    private void endAttempt(Job job, String state, String error) {
        db.update("UPDATE job_attempts SET state=?,error_code=?,ended_at=now() WHERE job_id=? AND attempt=? AND state='RUNNING'", state, error, job.id(), job.attempts());
    }
    private void finish(Job job, String state, String error, String actor) {
        db.update("UPDATE platform_jobs SET state=?,error_code=?,lease_token=NULL,lease_until=NULL,completed_at=now(),updated_at=now() WHERE id=?", state, error, job.id());
        db.update("""
            INSERT INTO project_outbox(id,event_type,project_id,environment_id,job_id,request_id,target_revision,payload)
            VALUES (?,?,?,?,?,?,?,jsonb_build_object('state',?::text,'errorCode',?::text)) ON CONFLICT DO NOTHING
            """, UUID.randomUUID(), "job."+state.toLowerCase(java.util.Locale.ROOT), job.projectId(), job.environmentId(), job.id(), job.requestId(), job.targetRevision(), state, error);
        audit(actor, "job."+state.toLowerCase(java.util.Locale.ROOT), job.id(), job.environmentId());
    }
    private void audit(String actor, String action, UUID id, UUID environmentId) {
        db.update("INSERT INTO audit_events(actor,action,target_id,environment_id) VALUES (?,?,?,?)", actor, action, id, environmentId);
    }
    private static Instant instant(ResultSet rs, String name) throws SQLException {
        var value = rs.getTimestamp(name); return value == null ? null : value.toInstant();
    }
    private static Job map(ResultSet rs) throws SQLException {
        return new Job(rs.getObject("id",UUID.class), rs.getObject("project_id",UUID.class), rs.getObject("environment_id",UUID.class),
            rs.getString("type"),rs.getString("state"),rs.getLong("target_revision"),rs.getInt("attempts"),rs.getInt("max_attempts"),
            instant(rs,"next_run_at"),instant(rs,"lease_until"),rs.getString("request_id"),rs.getString("error_code"),
            rs.getObject("retry_of",UUID.class),instant(rs,"created_at"),instant(rs,"updated_at"),instant(rs,"completed_at"));
    }
}
