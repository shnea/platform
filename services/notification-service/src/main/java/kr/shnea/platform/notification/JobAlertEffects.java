package kr.shnea.platform.notification;

import java.sql.Timestamp;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

// Runs within the receipt transaction and its per-environment lock. No network calls here.
final class JobAlertEffects {
    static void apply(JdbcTemplate db,JobEvents.Event event) {
        if(event.type().equals("job.cancelled"))return;
        var successes=db.queryForList("""
            SELECT id FROM received_job_events WHERE project_id=? AND environment_id=? AND event_type='job.succeeded'
            AND ((envelope->>'targetRevision')::bigint,occurred_at)>(?,?)
            ORDER BY (envelope->>'targetRevision')::bigint,occurred_at,id LIMIT 1
            """,event.projectId(),event.environmentId(),event.targetRevision(),Timestamp.from(event.occurredAt()));
        if(event.type().equals("job.failed")) {
            UUID recovered=successes.isEmpty()?null:(UUID)successes.getFirst().get("id");
            db.update("INSERT INTO operational_alerts(event_id,project_id,environment_id,code,recovered_by) VALUES (?,?,?,'JOB_FAILED',?)",
                event.id(),event.projectId(),event.environmentId(),recovered);
            if(recovered!=null || newerFailure(db,event)) { decision(db,event.id(),"STALE");return; }
            queue(db,event,false);
        } else {
            // A success recovers older failures only. Same-time failure wins rather than falsely declaring recovery.
            var failures=db.queryForList("""
                SELECT a.event_id FROM operational_alerts a JOIN received_job_events f ON f.id=a.event_id
                WHERE a.project_id=? AND a.environment_id=? AND a.code='JOB_FAILED' AND a.recovered_by IS NULL
                AND ((f.envelope->>'targetRevision')::bigint,f.occurred_at)<(?,?)
                ORDER BY (f.envelope->>'targetRevision')::bigint DESC,f.occurred_at DESC,f.id LIMIT 1
                """,event.projectId(),event.environmentId(),event.targetRevision(),Timestamp.from(event.occurredAt()));
            if(failures.isEmpty())return;
            db.update("""
                UPDATE operational_alerts a SET recovered_by=? FROM received_job_events f WHERE f.id=a.event_id
                AND a.project_id=? AND a.environment_id=? AND a.code='JOB_FAILED' AND a.recovered_by IS NULL
                AND ((f.envelope->>'targetRevision')::bigint,f.occurred_at)<(?,?)
                """,event.id(),event.projectId(),event.environmentId(),event.targetRevision(),Timestamp.from(event.occurredAt()));
            db.update("INSERT INTO operational_alerts(event_id,project_id,environment_id,code,related_alert_id) VALUES (?,?,?,'JOB_RECOVERED',?)",
                event.id(),event.projectId(),event.environmentId(),failures.getFirst().get("event_id"));
            if(!successes.isEmpty() || newerFailure(db,event)) {decision(db,event.id(),"STALE");return;}
            queue(db,event,true);
        }
    }
    private static boolean newerFailure(JdbcTemplate db,JobEvents.Event event) {
        return Boolean.TRUE.equals(db.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM received_job_events WHERE project_id=? AND environment_id=? AND event_type='job.failed' AND id<>?
            AND ((envelope->>'targetRevision')::bigint,occurred_at)>=(?,?))
            """,Boolean.class,event.projectId(),event.environmentId(),event.id(),event.targetRevision(),Timestamp.from(event.occurredAt())));
    }
    private static void queue(JdbcTemplate db,JobEvents.Event event,boolean recovery) {
        var policies=db.queryForList("SELECT * FROM alert_email_settings WHERE project_id=? AND environment_id=? AND enabled",event.projectId(),event.environmentId());
        if(policies.isEmpty())return;
        var policy=policies.getFirst();
        if(recovery && !(boolean)policy.get("recovery_enabled")) {decision(db,event.id(),"RECOVERY_DISABLED");return;}
        if(!recovery && Boolean.TRUE.equals(db.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM alert_email_deliveries d JOIN operational_alerts a ON a.event_id=d.event_id
            WHERE a.project_id=? AND a.environment_id=? AND a.code='JOB_FAILED' AND a.recovered_by IS NULL
            AND d.state<>'CANCELLED' AND d.created_at>now()-(? * interval '1 minute'))
            """,Boolean.class,event.projectId(),event.environmentId(),policy.get("suppression_minutes")))) {
            decision(db,event.id(),"SUPPRESSED");return;
        }
        db.update("INSERT INTO alert_email_deliveries(event_id,recipient,settings_revision,state) VALUES (?,?,?,'PENDING')",
            event.id(),policy.get("recipient"),policy.get("revision"));
        decision(db,event.id(),"QUEUED");
    }
    private static void decision(JdbcTemplate db,UUID id,String value) {
        db.update("UPDATE operational_alerts SET email_decision=? WHERE event_id=?",value,id);
    }
}
