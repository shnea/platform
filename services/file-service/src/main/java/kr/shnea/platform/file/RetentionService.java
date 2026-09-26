package kr.shnea.platform.file;

import java.time.Instant;
import java.util.*;
import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@Service
class RetentionService {
    record Policy(String code,String displayName,Integer periodValue,String periodUnit,boolean enabled,long revision) {}
    record Settings(boolean enabled,int graceDays,long revision,Instant lastCheckedAt,String errorCode) {}
    record Overview(Settings settings,List<Policy> policies,long eligibleFiles) {}
    record Impact(long affectedFiles,long eligibleFiles) {}
    record SavePolicy(Policy policy,long expectedEligibleFiles) {}
    record SaveSettings(boolean enabled,int graceDays,long revision,long expectedEligibleFiles) {}
    record Candidate(UUID fileId,String originalName,String retentionCode,Instant lastUsedAt,Instant dueAt,Instant deleteAfter) {}
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final FileAccess access;
    private final JsonMapper json=new JsonMapper();
    RetentionService(JdbcTemplate db,TransactionTemplate tx,FileAccess access) {this.db=db;this.tx=tx;this.access=access;}
    static void initialize(JdbcTemplate db,UUID env) {
        db.update("INSERT INTO file_retention_settings(environment_id) VALUES (?) ON CONFLICT DO NOTHING",env);
        db.update("""
            INSERT INTO file_retention_policies(environment_id,code,display_name,period_value,period_unit)
            VALUES (?,'default','기본 보관',1,'YEAR'),(?,'tmp','임시 보관',1,'DAY'),(?,'영구','영구 보관',NULL,'FOREVER') ON CONFLICT DO NOTHING
            """,env,env,env);
    }
    static void lock(JdbcTemplate db,UUID env) {
        db.execute("SET LOCAL lock_timeout='5s'");
        db.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?::text,94721))",env.toString());
    }
    Overview overview(FileAccess.Context context) {
        initialize(db,context.environmentId());
        return new Overview(settings(context.environmentId()),policies(context.environmentId()),eligible(context.environmentId()));
    }
    List<Policy> policies(UUID env) {
        return db.query("SELECT * FROM file_retention_policies WHERE environment_id=? ORDER BY CASE code WHEN 'default' THEN 0 WHEN 'tmp' THEN 1 WHEN '영구' THEN 2 ELSE 3 END,code",
            (r,n)->new Policy(r.getString("code"),r.getString("display_name"),(Integer)r.getObject("period_value"),r.getString("period_unit"),r.getBoolean("enabled"),r.getLong("revision")),env);
    }
    private Settings settings(UUID env) {
        return db.queryForObject("SELECT * FROM file_retention_settings WHERE environment_id=?",(r,n)->new Settings(r.getBoolean("enabled"),r.getInt("grace_days"),r.getLong("revision"),r.getTimestamp("last_checked_at")==null?null:r.getTimestamp("last_checked_at").toInstant(),r.getString("error_code")),env);
    }
    private long eligible(UUID env) {
        return db.queryForObject("""
            SELECT count(*) FROM files f JOIN file_retention_policies p ON p.environment_id=f.environment_id AND p.code=f.retention_code
            WHERE f.environment_id=? AND f.state='READY' AND file_retention_due(f.last_used_at,p.period_value,p.period_unit)<=now()
            """,Long.class,env);
    }
    Impact preview(FileAccess.Context context,Policy input) {
        validate(input);initialize(db,context.environmentId());
        return tx.execute(status->{lock(db,context.environmentId());current(context.environmentId(),input);return impact(context.environmentId(),input);});
    }
    private Policy current(UUID env,Policy input) {
        Policy old=policies(env).stream().filter(p->p.code().equals(input.code())).findFirst().orElse(null);
        if((old==null?0:old.revision())!=input.revision()) throw conflict();
        return old;
    }
    private Impact impact(UUID env,Policy input) {
        return db.queryForObject("""
            SELECT count(*) AS total,count(*) FILTER(WHERE file_retention_due(last_used_at,?,?)<=now()) AS due
            FROM files WHERE environment_id=? AND retention_code=? AND state='READY'
            """,(r,n)->new Impact(r.getLong("total"),r.getLong("due")),input.periodValue(),input.periodUnit(),env,input.code());
    }
    Overview savePolicy(FileAccess.Context context,SavePolicy save) {
        if(save==null)throw FileFailure.invalid();validate(save.policy());
        return tx.execute(status->{
            UUID env=context.environmentId();lock(db,env);initialize(db,env);
            Policy p=save.policy(),old=current(env,p);Impact impact=impact(env,p);
            if(impact.eligibleFiles()!=save.expectedEligibleFiles())throw conflict();
            if(old==null && policies(env).size()>=100)throw new FileFailure("FILE_RETENTION_LIMIT",409,"환경당 보존 코드는 최대 100개입니다.");
            db.update("""
                INSERT INTO file_retention_policies(environment_id,code,display_name,period_value,period_unit,enabled,revision) VALUES (?,?,?,?,?,?,1)
                ON CONFLICT(environment_id,code) DO UPDATE SET display_name=excluded.display_name,period_value=excluded.period_value,
                period_unit=excluded.period_unit,enabled=excluded.enabled,revision=file_retention_policies.revision+1
                """,env,p.code(),p.displayName().trim(),p.periodValue(),p.periodUnit(),p.enabled());
            // Every period change starts a fresh grace period for already stored files.
            db.update("UPDATE files SET retention_marked_at=NULL WHERE environment_id=? AND retention_code=? AND state='READY'",env,p.code());
            audit(context,"retention.policy.saved",p.code(),old,p,impact.affectedFiles());
            return overview(context);
        });
    }
    Overview saveSettings(FileAccess.Context context,SaveSettings input) {
        if(input==null || input.graceDays()<1 || input.graceDays()>30)throw FileFailure.invalid();
        return tx.execute(status->{
            UUID env=context.environmentId();lock(db,env);initialize(db,env);Settings old=settings(env);
            if(old.revision()!=input.revision() || eligible(env)!=input.expectedEligibleFiles())throw conflict();
            db.update("UPDATE file_retention_settings SET enabled=?,grace_days=?,revision=revision+1,error_code=NULL WHERE environment_id=?",input.enabled(),input.graceDays(),env);
            db.update("UPDATE files SET retention_marked_at=NULL WHERE environment_id=? AND state='READY'",env);
            audit(context,"retention.settings.saved",null,old,input,input.expectedEligibleFiles());
            return overview(context);
        });
    }
    List<Candidate> candidates(FileAccess.Context context,int offset) {
        if(offset<0 || offset>100000)throw FileFailure.invalid();
        initialize(db,context.environmentId());
        return db.query("""
            SELECT f.*,file_retention_due(f.last_used_at,p.period_value,p.period_unit) AS due_at,
              CASE WHEN s.enabled THEN f.retention_marked_at+make_interval(days=>s.grace_days) END AS delete_after
            FROM files f JOIN file_retention_policies p ON p.environment_id=f.environment_id AND p.code=f.retention_code
            JOIN file_retention_settings s ON s.environment_id=f.environment_id
            WHERE f.environment_id=? AND f.state='READY' AND file_retention_due(f.last_used_at,p.period_value,p.period_unit)<=now()
            ORDER BY due_at,f.id LIMIT 20 OFFSET ?
            """,(r,n)->new Candidate(r.getObject("id",UUID.class),r.getString("original_name"),r.getString("retention_code"),r.getTimestamp("last_used_at").toInstant(),r.getTimestamp("due_at").toInstant(),r.getTimestamp("delete_after")==null?null:r.getTimestamp("delete_after").toInstant()),context.environmentId(),offset);
    }
    List<Map<String,Object>> history(FileAccess.Context context) {
        return db.queryForList("SELECT id,actor,action,policy_code,before_value::text,after_value::text,affected_files,request_id,created_at FROM file_retention_audit WHERE environment_id=? ORDER BY id DESC LIMIT 50",context.environmentId());
    }
    private void audit(FileAccess.Context c,String action,String code,Object before,Object after,long affected) {
        db.update("INSERT INTO file_retention_audit(environment_id,actor,action,policy_code,before_value,after_value,affected_files,request_id) VALUES (?,?,?,?,?::jsonb,?::jsonb,?,?)",c.environmentId(),c.actor(),action,code,before==null?null:json.writeValueAsString(before),json.writeValueAsString(after),affected,MDC.get("requestId"));
    }
    static FileFailure conflict(){return new FileFailure("FILE_RETENTION_CONFLICT",409,"보존 설정이나 삭제 대상 수가 변경되었습니다. 다시 조회해 영향을 확인해 주세요.");}
    private static void validate(Policy p) {
        if(p==null || p.code()==null || !p.code().matches("(?:[a-z][a-z0-9_-]{0,59}|영구)") || p.displayName()==null || p.displayName().isBlank() || p.displayName().length()>120 || p.revision()<0 || p.periodUnit()==null)throw FileFailure.invalid();
        if(Set.of("default","tmp","영구").contains(p.code()) && !p.enabled())throw FileFailure.invalid();
        if(p.code().equals("영구")) {if(!p.periodUnit().equals("FOREVER") || p.periodValue()!=null)throw FileFailure.invalid();return;}
        int max=switch(p.periodUnit()){case "DAY"->36500;case "MONTH"->1200;case "YEAR"->100;default->0;};
        if(p.periodValue()==null || p.periodValue()<1 || p.periodValue()>max)throw FileFailure.invalid();
    }
    @Scheduled(fixedDelayString="${platform.files.cleanup-ms:60000}",initialDelay=15000)
    void sweep() {
        for(UUID env:db.queryForList("SELECT environment_id FROM file_retention_settings WHERE enabled ORDER BY last_checked_at NULLS FIRST LIMIT 20",UUID.class)) {
            try {access.requireActive(env); sweepEnvironment(env);}
            catch(RuntimeException error) {db.update("UPDATE file_retention_settings SET last_checked_at=now(),error_code='FILE_RETENTION_CHECK_FAILED' WHERE environment_id=?",env);}
        }
    }
    void sweepEnvironment(UUID env) {
        tx.executeWithoutResult(status->{
            lock(db,env);Settings config=settings(env);if(!config.enabled())return;
            // The environment lock serializes policy changes, grace changes and the final eligibility check.
            var ids=db.queryForList("""
                SELECT f.id FROM files f JOIN file_retention_policies p ON p.environment_id=f.environment_id AND p.code=f.retention_code
                WHERE f.environment_id=? AND f.state='READY' AND file_retention_due(f.last_used_at,p.period_value,p.period_unit)<=now()
                AND NOT EXISTS(SELECT 1 FROM file_download_leases l WHERE l.file_id=f.id AND l.expires_at>now())
                ORDER BY CASE WHEN f.retention_marked_at+make_interval(days=>?)<=now() THEN 0 WHEN f.retention_marked_at IS NULL THEN 1 ELSE 2 END,f.last_used_at LIMIT 100 FOR UPDATE OF f SKIP LOCKED
                """,UUID.class,env,config.graceDays());
            for(UUID id:ids) {
                int deleted=db.update("UPDATE files SET state='DELETED' WHERE id=? AND retention_marked_at+make_interval(days=>?)<=now()",id,config.graceDays());
                if(deleted==1) {
                    db.update("DELETE FROM file_download_tickets WHERE file_id=?",id);
                    db.update("INSERT INTO file_audit(file_id,environment_id,actor,action) VALUES (?,?,'system:retention','file.retention.deleted')",id,env);
                } else {
                    int marked=db.update("UPDATE files SET retention_marked_at=now() WHERE id=? AND retention_marked_at IS NULL",id);
                    if(marked==1)db.update("INSERT INTO file_audit(file_id,environment_id,actor,action) VALUES (?,?,'system:retention','file.retention.queued')",id,env);
                }
            }
            db.update("UPDATE file_retention_settings SET last_checked_at=now(),error_code=NULL WHERE environment_id=?",env);
        });
    }
}
