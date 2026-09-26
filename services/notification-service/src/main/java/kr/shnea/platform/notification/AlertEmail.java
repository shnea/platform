package kr.shnea.platform.notification;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
class AlertEmail {
    record Settings(boolean enabled,String recipient,int suppressionMinutes,boolean recoveryEnabled,long revision,
                    String deliveryMode,Instant updatedAt,String updatedBy,String requestId) {}
    record Save(@NotNull UUID projectId,@NotNull UUID environmentId,@NotNull @Pattern(regexp="DEV|PROD") String environmentKind,
                boolean enabled,@NotNull @Email @Size(max=320) String recipient,@Min(1) @Max(1440) int suppressionMinutes,
                boolean recoveryEnabled,@Min(0) long revision,@NotBlank @Size(max=200) String actor,
                @NotNull @Pattern(regexp="[a-f0-9]{32}") String requestId) {}
    record Delivery(UUID eventId,String code,String recipient,String state,Instant createdAt,Instant startedAt,
                    Instant finishedAt,String providerId) {}
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final NcpMail mail;
    private final String mode;
    AlertEmail(JdbcTemplate db,TransactionTemplate tx,NcpMail mail,@Value("${PLATFORM_MODE:prod}") String mode) {
        if(!List.of("dev","prod").contains(mode))throw new IllegalArgumentException("Invalid platform mode");
        this.db=db;this.tx=tx;this.mail=mail;this.mode=mode;
    }
    static void lock(JdbcTemplate db,UUID environment) {
        db.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",environment.toString());
    }
    String deliveryMode(String kind) {
        if ("dev".equals(mode) && "DEV".equals(kind)) return "MOCK";
        if ("prod".equals(mode) && "PROD".equals(kind) && mail.ready()) return "NCP";
        return "BLOCKED";
    }
    @GetMapping("/internal/v1/operational-alerts/email-settings")
    Settings settings(@RequestParam UUID projectId,@RequestParam UUID environmentId,@RequestParam String environmentKind) {
        if (!List.of("DEV","PROD").contains(environmentKind)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        var rows=db.query("SELECT * FROM alert_email_settings WHERE project_id=? AND environment_id=?",(rs,n)->
            new Settings(rs.getBoolean("enabled"),rs.getString("recipient"),rs.getInt("suppression_minutes"),rs.getBoolean("recovery_enabled"),
                rs.getLong("revision"),deliveryMode(environmentKind),instant(rs,"updated_at"),rs.getString("actor"),rs.getString("request_id")),projectId,environmentId);
        return rows.isEmpty()?new Settings(false,"",15,true,0,deliveryMode(environmentKind),null,null,null):rows.getFirst();
    }
    @PutMapping("/internal/v1/operational-alerts/email-settings")
    Settings save(@Valid @RequestBody Save request) {
        String recipient=request.recipient().strip();
        if ((!recipient.isEmpty() && !recipient.matches("[^\\s@<>(),;\\p{Cntrl}]+@[^\\s@<>(),;\\p{Cntrl}]+\\.[^\\s@<>(),;\\p{Cntrl}]+")) ||
                (request.enabled() && (recipient.isEmpty() || deliveryMode(request.environmentKind()).equals("BLOCKED"))))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        return tx.execute(status->{
            lock(db,request.environmentId());
            var current=db.queryForList("SELECT project_id,revision FROM alert_email_settings WHERE environment_id=?",request.environmentId());
            if ((!current.isEmpty() && (!current.getFirst().get("project_id").equals(request.projectId()) ||
                    ((Number)current.getFirst().get("revision")).longValue()!=request.revision())) ||
                    (current.isEmpty() && request.revision()!=0)) throw new ResponseStatusException(HttpStatus.CONFLICT);
            db.update("""
                INSERT INTO alert_email_settings(project_id,environment_id,environment_kind,enabled,recipient,suppression_minutes,recovery_enabled,revision,actor,request_id)
                VALUES (?,?,?,?,?,?,?,?,?,?) ON CONFLICT(environment_id) DO UPDATE SET
                  environment_kind=excluded.environment_kind,enabled=excluded.enabled,recipient=excluded.recipient,
                  suppression_minutes=excluded.suppression_minutes,recovery_enabled=excluded.recovery_enabled,
                  revision=excluded.revision,actor=excluded.actor,request_id=excluded.request_id,updated_at=now()
                """,request.projectId(),request.environmentId(),request.environmentKind(),request.enabled(),recipient,
                request.suppressionMinutes(),request.recoveryEnabled(),request.revision()+1,request.actor(),request.requestId());
            db.update("INSERT INTO alert_email_settings_audit SELECT environment_id,revision,to_jsonb(s) FROM alert_email_settings s WHERE environment_id=?",request.environmentId());
            db.update("""
                UPDATE alert_email_deliveries d SET state='CANCELLED',finished_at=now() FROM operational_alerts a
                WHERE d.event_id=a.event_id AND a.environment_id=? AND d.state='PENDING'
                """,request.environmentId());
            return settings(request.projectId(),request.environmentId(),request.environmentKind());
        });
    }
    @GetMapping("/internal/v1/operational-alerts/email-deliveries")
    List<Delivery> deliveries(@RequestParam UUID projectId,@RequestParam UUID environmentId,
            @RequestParam(defaultValue="20") int limit,@RequestParam(defaultValue="0") int offset) {
        if(limit<1 || limit>100 || offset<0 || offset>1_000_000)throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        return db.query("""
            SELECT d.*,a.code FROM alert_email_deliveries d JOIN operational_alerts a ON a.event_id=d.event_id
            WHERE a.project_id=? AND a.environment_id=? ORDER BY d.created_at DESC,d.event_id LIMIT ? OFFSET ?
            """,(rs,n)->new Delivery(rs.getObject("event_id",UUID.class),rs.getString("code"),rs.getString("recipient"),rs.getString("state"),
                instant(rs,"created_at"),instant(rs,"started_at"),instant(rs,"finished_at"),rs.getString("provider_id")),projectId,environmentId,limit,offset);
    }
    record Pending(UUID id,UUID projectId,UUID environmentId,UUID jobId,String code,String recipient,String deliveryMode) {}
    Pending claim() {
        return tx.execute(status->{
            // SENDING is never retried: a crash or timeout may have happened after provider acceptance.
            db.update("UPDATE alert_email_deliveries SET state='UNKNOWN',finished_at=now() WHERE state='SENDING' AND started_at<now()-interval '2 minutes'");
            var rows=db.query("""
                SELECT d.*,a.project_id,a.environment_id,a.code,e.target_id,s.enabled,s.revision,s.environment_kind
                FROM alert_email_deliveries d JOIN operational_alerts a ON a.event_id=d.event_id
                JOIN received_job_events e ON e.id=a.event_id JOIN alert_email_settings s ON s.environment_id=a.environment_id AND s.project_id=a.project_id
                WHERE d.state='PENDING' ORDER BY d.created_at,d.event_id LIMIT 1 FOR UPDATE OF d SKIP LOCKED
                """,(rs,n)->new Object[]{new Pending(rs.getObject("event_id",UUID.class),rs.getObject("project_id",UUID.class),
                    rs.getObject("environment_id",UUID.class),rs.getObject("target_id",UUID.class),rs.getString("code"),
                    rs.getString("recipient"),deliveryMode(rs.getString("environment_kind"))),
                    rs.getBoolean("enabled") && rs.getLong("settings_revision")==rs.getLong("revision")});
            if(rows.isEmpty())return null;
            var pending=(Pending)rows.getFirst()[0];
            boolean valid=(boolean)rows.getFirst()[1];
            db.update("UPDATE alert_email_deliveries SET state=?,started_at=now(),finished_at=? WHERE event_id=?",
                valid?"SENDING":"CANCELLED",valid?null:java.sql.Timestamp.from(Instant.now()),pending.id());
            return valid?pending:null;
        });
    }
    @Scheduled(fixedDelayString="${ALERT_EMAIL_POLL_MS:3000}")
    void dispatch() {
        for(int i=0;i<10;i++) {
            Pending p=claim();if(p==null)return;
            NcpMail.Outcome result=switch(p.deliveryMode()) {
                case "MOCK" -> new NcpMail.Outcome("MOCK",null);
                case "NCP" -> mail.send(p.recipient(),"[플랫폼] "+switch(p.code()) {
                    case "JOB_RECOVERED" -> "환경 반영 복구";
                    case "JOB_BACKLOGGED" -> "환경 반영 작업 대기 적체";
                    case "JOB_BACKLOG_RECOVERED" -> "작업 대기 적체 해소";
                    default -> "환경 반영 작업 최종 실패";
                },
                    "프로젝트: "+p.projectId()+"\n환경: "+p.environmentId()+"\n작업: "+p.jobId()+"\n알림: "+p.id()+
                    "\n관리자 화면의 운영 알림에서 상세 내용을 확인해 주세요.");
                default -> new NcpMail.Outcome("BLOCKED",null);
            };
            db.update("UPDATE alert_email_deliveries SET state=?,provider_id=?,finished_at=now() WHERE event_id=? AND state='SENDING'",
                result.state(),result.providerId(),p.id());
        }
    }
    private static Instant instant(ResultSet rs,String field) throws SQLException {
        var value=rs.getTimestamp(field);return value==null?null:value.toInstant();
    }
}
