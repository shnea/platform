package kr.shnea.platform.notification;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.json.JsonMapper;

@RestController
class JobEvents {
    record Payload(String state, String errorCode) {}
    record Event(UUID id, String type, int schemaVersion, String source, UUID projectId, UUID environmentId,
                 UUID targetId, long targetRevision, String requestId, UUID causationId, Instant occurredAt, Payload payload) {}
    record Receipt(UUID id, String state) {}
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final JsonMapper json;
    JobEvents(JdbcTemplate db, TransactionTemplate tx, JsonMapper json) { this.db=db; this.tx=tx; this.json=json; }

    @PostMapping("/internal/v1/events/jobs")
    Receipt receive(@RequestBody Event event) {
        if (event.schemaVersion()!=1) throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT);
        if (event.id()==null || event.projectId()==null || event.environmentId()==null || event.targetId()==null ||
            event.targetRevision()<0 || event.occurredAt()==null || !"project-service".equals(event.source()) ||
            event.causationId()!=null || event.requestId()==null || !event.requestId().matches("[a-f0-9]{32}") ||
            event.payload()==null || event.type()==null || !List.of("job.succeeded","job.failed","job.cancelled").contains(event.type()) ||
            !event.type().substring(4).toUpperCase(java.util.Locale.ROOT).equals(event.payload().state()) ||
            (event.payload().errorCode()!=null && !List.of("ENVIRONMENT_PROVISION_FAILED","JOB_EXECUTION_FAILED",
                "WORKER_INTERRUPTED","RETRY_EXHAUSTED","JOB_TARGET_CHANGED").contains(event.payload().errorCode())))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        String envelope=json.writeValueAsString(event);
        return tx.execute(status -> {
            int inserted=db.update("""
                INSERT INTO received_job_events(id,project_id,environment_id,target_id,event_type,envelope,occurred_at)
                VALUES (?,?,?,?,?,?::jsonb,?) ON CONFLICT DO NOTHING
                """, event.id(),event.projectId(),event.environmentId(),event.targetId(),event.type(),envelope,
                java.sql.Timestamp.from(event.occurredAt()));
            if (inserted==0) {
                Boolean same=db.queryForObject("SELECT EXISTS(SELECT 1 FROM received_job_events WHERE id=? AND envelope=?::jsonb)",
                    Boolean.class,event.id(),envelope);
                if (!Boolean.TRUE.equals(same)) throw new ResponseStatusException(HttpStatus.CONFLICT);
            } else if (event.type().equals("job.failed")) {
                // The receipt and effect commit together. No external sending takes place in this consumer.
                db.update("INSERT INTO operational_alerts(event_id,project_id,environment_id,code) VALUES (?,?,?,'JOB_FAILED')",
                    event.id(),event.projectId(),event.environmentId());
            }
            return new Receipt(event.id(),"ACCEPTED");
        });
    }
}
