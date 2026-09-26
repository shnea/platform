package kr.shnea.platform.project;

import java.time.Instant;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.UUID;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import kr.shnea.platform.http.RequestTrace;

@RestController
class JobBacklog {
    record Settings(boolean enabled,int thresholdSeconds,int consecutiveChecks,long revision,Instant updatedAt,
                    String updatedBy,String requestId,Instant lastCheckedAt,int breachChecks,int clearChecks,
                    UUID activeJobId,UUID activeEventId,boolean monitoringAvailable) {}
    record Save(boolean enabled,@Min(60) @Max(86400) int thresholdSeconds,@Min(1) @Max(10) int consecutiveChecks,@Min(0) long revision) {}
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final boolean available;
    JobBacklog(JdbcTemplate db,TransactionTemplate tx,@Value("${platform.events.enabled:false}") boolean available) {
        this.db=db;this.tx=tx;this.available=available;
    }
    @GetMapping("/api/v1/admin/environments/{id}/job-backlog-settings")
    Settings settings(@PathVariable UUID id) {
        if(!Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM environments WHERE id=?)",Boolean.class,id)))throw ApiCode.RESOURCE_NOT_FOUND.failure();
        return db.query("SELECT * FROM job_backlog_settings WHERE environment_id=?",(rs,n)->map(rs),id).stream().findFirst()
            .orElse(new Settings(false,300,2,0,null,null,null,null,0,0,null,null,available));
    }
    @PutMapping("/api/v1/admin/environments/{id}/job-backlog-settings")
    Settings update(@PathVariable UUID id,@Valid @RequestBody Save value,@AuthenticationPrincipal Jwt actor,HttpServletRequest request) {
        return save(id,value,actor.getSubject(),RequestTrace.id(request));
    }
    Settings save(UUID id,Save value,String actor,String request) {
        if(value.thresholdSeconds()<60 || value.thresholdSeconds()>86400 || value.consecutiveChecks()<1 || value.consecutiveChecks()>10 || value.revision()<0)
            throw ApiCode.INVALID_REQUEST.failure();
        if(value.enabled() && !available)throw ApiCode.SERVICE_UNAVAILABLE.failure();
        return tx.execute(status->{
            if(db.queryForList("SELECT id FROM environments WHERE id=? FOR UPDATE",id).isEmpty())throw ApiCode.RESOURCE_NOT_FOUND.failure();
            db.update("INSERT INTO job_backlog_settings(environment_id) VALUES (?) ON CONFLICT DO NOTHING",id);
            var current=db.queryForObject("SELECT * FROM job_backlog_settings WHERE environment_id=? FOR UPDATE",(rs,n)->map(rs),id);
            if(current.revision()!=value.revision())throw ApiCode.SETTINGS_CHANGED.failure();
            if(current.activeEventId()!=null)event(id,current.activeJobId(),"job.backlog_closed",current.activeEventId(),current.revision(),"MONITORING_CHANGED");
            db.update("""
                UPDATE job_backlog_settings SET enabled=?,threshold_seconds=?,consecutive_checks=?,revision=revision+1,
                  updated_at=now(),actor=?,request_id=?,next_check_at=now(),last_checked_at=NULL,observed_job_id=NULL,observed_since=NULL,
                  breach_checks=0,clear_checks=0,active_job_id=NULL,active_event_id=NULL WHERE environment_id=?
                """,value.enabled(),value.thresholdSeconds(),value.consecutiveChecks(),actor,request,id);
            db.update("INSERT INTO job_backlog_settings_audit(environment_id,revision,enabled,threshold_seconds,consecutive_checks,actor,request_id) VALUES (?,?,?,?,?,?,?)",
                id,value.revision()+1,value.enabled(),value.thresholdSeconds(),value.consecutiveChecks(),actor,request);
            db.update("INSERT INTO audit_events(actor,action,target_id,environment_id) VALUES (?,'job.backlog.settings',?,?)",actor,id,id);
            return settings(id);
        });
    }
    boolean checkOne() {
        return Boolean.TRUE.equals(tx.execute(status->{
            var rows=db.queryForList("SELECT * FROM job_backlog_settings WHERE enabled AND next_check_at<=now() ORDER BY next_check_at,environment_id FOR UPDATE SKIP LOCKED LIMIT 1");
            if(rows.isEmpty())return false;
            var row=rows.getFirst();var env=(UUID)row.get("environment_id");
            var now=db.queryForObject("SELECT now()",java.sql.Timestamp.class).toInstant();
            var last=(java.sql.Timestamp)row.get("last_checked_at");
            boolean gap=last==null || last.toInstant().plusSeconds(90).isBefore(now);
            int threshold=((Number)row.get("threshold_seconds")).intValue(),required=((Number)row.get("consecutive_checks")).intValue();
            long revision=((Number)row.get("revision")).longValue();
            var active=(UUID)row.get("active_event_id");var activeJob=(UUID)row.get("active_job_id");
            var waiting=db.queryForList("""
                SELECT id,updated_at FROM platform_jobs WHERE environment_id=? AND state IN ('QUEUED','RETRY_WAIT')
                  AND next_run_at<=now() AND updated_at<=now()-(? * interval '1 second')
                """,env,threshold);
            UUID job=waiting.isEmpty()?null:(UUID)waiting.getFirst().get("id");
            var since=waiting.isEmpty()?null:(java.sql.Timestamp)waiting.getFirst().get("updated_at");
            int breach=0,clear=0;
            if(active!=null) {
                if(!activeJob.equals(job)) {
                    clear=(gap?0:((Number)row.get("clear_checks")).intValue())+1;
                    if(clear>=required) {
                        event(env,activeJob,"job.backlog_recovered",active,revision,null);active=null;activeJob=null;clear=0;
                    }
                }
            } else if(job!=null) {
                boolean same=job.equals(row.get("observed_job_id")) && Objects.equals(since,row.get("observed_since"));
                breach=(!gap && same?((Number)row.get("breach_checks")).intValue():0)+1;
                if(breach>=required) { active=event(env,job,"job.backlogged",null,revision,"JOB_QUEUE_DELAYED");activeJob=job;breach=0; }
            }
            db.update("""
                UPDATE job_backlog_settings SET last_checked_at=now(),next_check_at=now()+interval '30 seconds',
                observed_job_id=?,observed_since=?,breach_checks=?,clear_checks=?,active_event_id=?,active_job_id=? WHERE environment_id=?
                """,job,since,breach,clear,active,activeJob,env);
            return true;
        }));
    }
    private UUID event(UUID environment,UUID job,String type,UUID cause,long revision,String error) {
        UUID id=UUID.randomUUID();String request=UUID.randomUUID().toString().replace("-","");
        db.update("""
            INSERT INTO project_outbox(id,event_type,project_id,environment_id,job_id,request_id,target_revision,causation_id,payload)
            SELECT ?,?,project_id,environment_id,id,?,?,?,jsonb_build_object('state',?::text,'errorCode',?::text) FROM platform_jobs WHERE id=? AND environment_id=?
            """,id,type,request,revision,cause,type.substring(4).toUpperCase(java.util.Locale.ROOT),error,job,environment);
        db.update("INSERT INTO audit_events(actor,action,target_id,environment_id) VALUES ('job-backlog-monitor',?,?,?)",type,id,environment);
        return id;
    }
    private Settings map(ResultSet rs) throws SQLException {
        return new Settings(rs.getBoolean("enabled"),rs.getInt("threshold_seconds"),rs.getInt("consecutive_checks"),rs.getLong("revision"),
            instant(rs,"updated_at"),rs.getString("actor"),rs.getString("request_id"),instant(rs,"last_checked_at"),rs.getInt("breach_checks"),
            rs.getInt("clear_checks"),rs.getObject("active_job_id",UUID.class),rs.getObject("active_event_id",UUID.class),available);
    }
    private static Instant instant(ResultSet rs,String key) throws SQLException {var value=rs.getTimestamp(key);return value==null?null:value.toInstant();}
}
