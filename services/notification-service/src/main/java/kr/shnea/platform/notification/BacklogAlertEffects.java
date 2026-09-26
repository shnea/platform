package kr.shnea.platform.notification;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

// Shares the event receipt transaction/environment lock; unrelated final Job failures stay separate.
final class BacklogAlertEffects {
    static void apply(JdbcTemplate db,JobEvents.Event event) {
        boolean opening=event.type().equals("job.backlogged");
        var related=db.queryForList(opening
            ? "SELECT * FROM received_job_events WHERE event_type IN ('job.backlog_recovered','job.backlog_closed') AND envelope->>'causationId'=?"
            : "SELECT * FROM received_job_events WHERE id=?",opening?event.id().toString():event.causationId());
        for(var other:related) {
            if(!event.projectId().equals(other.get("project_id")) || !event.environmentId().equals(other.get("environment_id")) ||
                !event.targetId().equals(other.get("target_id")) || (!opening && !"job.backlogged".equals(other.get("event_type"))))
                throw new ResponseStatusException(HttpStatus.CONFLICT);
        }
        UUID relatedId=related.isEmpty()?null:(UUID)related.getFirst().get("id");
        String code=opening?"JOB_BACKLOGGED":event.type().equals("job.backlog_closed")?"JOB_BACKLOG_CLOSED":"JOB_BACKLOG_RECOVERED";
        db.update("INSERT INTO operational_alerts(event_id,project_id,environment_id,code,recovered_by,related_alert_id) VALUES (?,?,?,?,?,?)",
            event.id(),event.projectId(),event.environmentId(),code,opening?relatedId:null,opening?null:relatedId);
        if(opening && relatedId!=null) {
            db.update("UPDATE operational_alerts SET related_alert_id=? WHERE event_id=?",event.id(),relatedId);
            JobAlertEffects.decision(db,event.id(),"STALE");return;
        }
        if(!opening) {
            if(relatedId!=null)db.update("UPDATE operational_alerts SET recovered_by=? WHERE event_id=?",event.id(),relatedId);
            if(code.equals("JOB_BACKLOG_CLOSED")) {
                db.update("UPDATE alert_email_deliveries SET state='CANCELLED',finished_at=now() WHERE event_id=? AND state='PENDING'",event.causationId());
                JobAlertEffects.decision(db,event.id(),"MONITORING_CLOSED");return;
            }
        }
        // An out-of-order close is retained but does not send an unexplained recovery email.
        boolean newer=Boolean.TRUE.equals(db.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM received_job_events WHERE project_id=? AND environment_id=?
              AND event_type IN ('job.backlogged','job.backlog_recovered','job.backlog_closed') AND occurred_at>? AND id<>?)
            """,Boolean.class,event.projectId(),event.environmentId(),java.sql.Timestamp.from(event.occurredAt()),event.id()));
        if(newer || (!opening && relatedId==null)) {JobAlertEffects.decision(db,event.id(),"STALE");return;}
        JobAlertEffects.queue(db,event,!opening,"JOB_BACKLOGGED");
    }
}
