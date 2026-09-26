package kr.shnea.platform.notification;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
class OperationalAlerts {
    record Alert(UUID id, UUID projectId, UUID environmentId, UUID jobId, String code, String errorCode,
                 String requestId, Instant occurredAt, Instant createdAt, Instant acknowledgedAt,
                 String acknowledgedBy, String acknowledgementRequestId, UUID recoveredBy, UUID relatedAlertId, String emailDecision) {}
    record Acknowledge(@NotNull UUID projectId, @NotNull UUID environmentId,
                       @NotBlank @Size(max=200) String actor, @NotNull @Pattern(regexp="[a-f0-9]{32}") String requestId) {}
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private static final String SELECT="""
        SELECT a.*,e.target_id,e.occurred_at,e.envelope->>'requestId' AS request_id,
          e.envelope->'payload'->>'errorCode' AS error_code,
          k.acknowledged_at,k.actor,k.request_id AS acknowledgement_request_id
        FROM operational_alerts a JOIN received_job_events e ON e.id=a.event_id
        LEFT JOIN alert_acknowledgements k ON k.event_id=a.event_id
        """;
    OperationalAlerts(JdbcTemplate db, TransactionTemplate tx) {this.db=db;this.tx=tx;}
    @GetMapping("/internal/v1/operational-alerts")
    List<Alert> list(@RequestParam UUID projectId,@RequestParam UUID environmentId,
            @RequestParam(required=false) Boolean acknowledged,@RequestParam(defaultValue="20") int limit,
            @RequestParam(defaultValue="0") int offset) {
        if(limit<1 || limit>100 || offset<0 || offset>1_000_000)throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        String filter=acknowledged==null ? "" : acknowledged ? " AND k.event_id IS NOT NULL" : " AND k.event_id IS NULL";
        return db.query(SELECT+" WHERE a.project_id=? AND a.environment_id=?"+filter+" ORDER BY a.created_at DESC,a.event_id LIMIT ? OFFSET ?",
            (rs,n)->map(rs),projectId,environmentId,limit,offset);
    }
    @PostMapping("/internal/v1/operational-alerts/{id}/acknowledge")
    Alert acknowledge(@PathVariable UUID id,@Valid @RequestBody Acknowledge request) {
        return tx.execute(status->{
            var owned=db.queryForList("SELECT event_id FROM operational_alerts WHERE event_id=? AND project_id=? AND environment_id=? FOR UPDATE",
                id,request.projectId(),request.environmentId());
            if(owned.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND);
            db.update("INSERT INTO alert_acknowledgements(event_id,actor,request_id) VALUES (?,?,?) ON CONFLICT DO NOTHING",id,request.actor(),request.requestId());
            return db.queryForObject(SELECT+" WHERE a.event_id=?",(rs,n)->map(rs),id);
        });
    }
    private static Instant instant(ResultSet rs,String field) throws SQLException {
        var timestamp=rs.getTimestamp(field);return timestamp==null?null:timestamp.toInstant();
    }
    private static Alert map(ResultSet rs) throws SQLException {
        return new Alert(rs.getObject("event_id",UUID.class),rs.getObject("project_id",UUID.class),rs.getObject("environment_id",UUID.class),
            rs.getObject("target_id",UUID.class),rs.getString("code"),rs.getString("error_code"),rs.getString("request_id"),
            instant(rs,"occurred_at"),instant(rs,"created_at"),instant(rs,"acknowledged_at"),rs.getString("actor"),rs.getString("acknowledgement_request_id"),
            rs.getObject("recovered_by",UUID.class),rs.getObject("related_alert_id",UUID.class),rs.getString("email_decision"));
    }
}
