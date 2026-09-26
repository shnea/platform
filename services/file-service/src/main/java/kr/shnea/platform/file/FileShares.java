package kr.shnea.platform.file;

import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.Semaphore;
import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.Pbkdf2PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
class FileShares {
    record Create(String password,Integer expiresInDays) {}
    record Share(UUID shareId,Instant createdAt,Instant expiresAt,String state,String url) {}
    private record Secret(UUID fileId,String hash,Instant expiresAt) {}
    private final JdbcTemplate db; private final TransactionTemplate tx;
    private final FilesService files; private final FileAccess access; private final FileViews views;
    private final Pbkdf2PasswordEncoder passwords=new Pbkdf2PasswordEncoder("",16,600000,Pbkdf2PasswordEncoder.SecretKeyFactoryAlgorithm.PBKDF2WithHmacSHA256);
    // Bound expensive password checks per instance; persistent per-link limits also apply across restarts.
    private final Semaphore hashing=new Semaphore(2);
    FileShares(JdbcTemplate db,TransactionTemplate tx,FilesService files,FileAccess access,FileViews views) {
        this.db=db;this.tx=tx;this.files=files;this.access=access;this.views=views;
    }
    Share create(UUID file,FileAccess.Context context,Create input) {
        if(input==null)throw FileFailure.invalid();validatePassword(input.password());
        int days=input.expiresInDays()==null?7:input.expiresInDays();
        if(days<1||days>30)throw new FileFailure("FILE_SHARE_INVALID_EXPIRY",400,"공유 기간은 1~30일로 지정해 주세요.");
        files.detail(file,context);String encoded;
        acquire();try{encoded=passwords.encode(input.password());}finally{hashing.release();}
        return tx.execute(s->{
            lockFile(file);var info=files.detail(file,context);
            if(!info.visibility().equals("PRIVATE"))throw new FileFailure("FILE_SHARE_PRIVATE_REQUIRED",409,"비밀번호 공유는 비공개 파일에서 만들 수 있습니다. 먼저 파일을 비공개로 변경해 주세요.");
            if(db.queryForObject("SELECT count(*) FROM file_shares s JOIN files f ON f.id=s.file_id WHERE s.file_id=? AND s.revoked_at IS NULL AND s.expires_at>now() AND s.access_revision=f.access_revision",Long.class,file)>=10)
                throw new FileFailure("FILE_SHARE_LIMIT",409,"사용 가능한 공유 링크는 파일당 최대 10개입니다. 기존 링크를 철회한 뒤 만들어 주세요.");
            UUID id=UUID.randomUUID();Instant end=Instant.now().plusSeconds(days*86400L);
            db.update("INSERT INTO file_shares(id,file_id,access_revision,password_hash,expires_at) SELECT ?,id,access_revision,?,? FROM files WHERE id=?",id,encoded,Timestamp.from(end),file);
            audit(file,id,context.actor(),"file.share.created");return findManaged(file,id);
        });
    }
    List<Share> list(UUID file,FileAccess.Context context) {
        files.detail(file,context);
        return db.query("SELECT s.*,f.access_revision AS current_revision,f.visibility FROM file_shares s JOIN files f ON f.id=s.file_id WHERE s.file_id=? ORDER BY (s.revoked_at IS NULL AND s.expires_at>now() AND s.access_revision=f.access_revision AND f.visibility='PRIVATE') DESC,s.created_at DESC,s.id LIMIT 50",this::share,file);
    }
    void revoke(UUID file,UUID id,FileAccess.Context context) {
        tx.executeWithoutResult(s->{
            lockFile(file);files.detail(file,context);findManaged(file,id);
            if(db.update("UPDATE file_shares SET revoked_at=now() WHERE id=? AND revoked_at IS NULL",id)>0) {
                db.update("DELETE FROM file_view_tokens WHERE share_id=?",id);
                audit(file,id,context.actor(),"file.share.revoked");
            }
        });
    }
    void available(UUID id) {var secret=secret(id);access.requireActive(files.downloadable(secret.fileId()).environment());secret(id);}
    FileViews.Links unlock(UUID id,String password) {
        validatePassword(password);acquire();
        try {
            var secret=secret(id);access.requireActive(files.downloadable(secret.fileId()).environment());
            // Reserve each attempt in its own committed statement: a rejected password must not roll it back.
            int reserved=db.update("""
                UPDATE file_shares SET attempts=CASE WHEN window_started_at<=now()-interval '15 minutes' THEN 1 ELSE attempts+1 END,
                    window_started_at=CASE WHEN window_started_at<=now()-interval '15 minutes' THEN now() ELSE window_started_at END
                WHERE id=? AND revoked_at IS NULL AND expires_at>now() AND (attempts<10 OR window_started_at<=now()-interval '15 minutes')
                """,id);
            if(reserved!=1){secret(id);throw new FileFailure("FILE_SHARE_RATE_LIMITED",429,"비밀번호 확인 횟수를 초과했습니다. 최대 15분 후 다시 시도해 주세요.");}
            if(!passwords.matches(password,secret.hash()))throw new FileFailure("FILE_SHARE_PASSWORD_INVALID",401,"공유 비밀번호가 맞지 않습니다. 전달받은 비밀번호를 확인해 주세요.");
            return tx.execute(s->{
                lockFile(secret.fileId());var current=secret(id);
                db.queryForList("SELECT id FROM file_shares WHERE id=? FOR UPDATE",id);current=secret(id);
                Instant end=Instant.now().plusSeconds(7200);if(current.expiresAt().isBefore(end))end=current.expiresAt();
                byte[] bytes=new byte[32];new SecureRandom().nextBytes(bytes);String token=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
                db.update("DELETE FROM file_view_tokens WHERE share_id=? AND greatest(expires_at,playback_expires_at)<=now()",id);
                db.update("DELETE FROM file_view_tokens WHERE token_hash IN (SELECT token_hash FROM file_view_tokens WHERE share_id=? ORDER BY expires_at DESC OFFSET 19)",id);
                db.update("INSERT INTO file_view_tokens(token_hash,file_id,access_revision,expires_at,playback_expires_at,share_id) SELECT ?,file_id,access_revision,?,?,id FROM file_shares WHERE id=?",FileViews.hash(token),Timestamp.from(end),Timestamp.from(end),id);
                audit(current.fileId(),id,"anonymous:share","file.share.opened");
                return views.links(current.fileId(),token,end);
            });
        } finally{hashing.release();}
    }
    private Secret secret(UUID id) {
        return db.query("""
            SELECT s.file_id,s.password_hash,s.expires_at FROM file_shares s JOIN files f ON f.id=s.file_id
            WHERE s.id=? AND s.revoked_at IS NULL AND s.expires_at>now() AND f.state='READY'
                AND f.visibility='PRIVATE' AND s.access_revision=f.access_revision
            """,(r,n)->new Secret(r.getObject(1,UUID.class),r.getString(2),r.getTimestamp(3).toInstant()),id).stream().findFirst().orElseThrow(FileShares::unavailable);
    }
    private Share findManaged(UUID file,UUID id) {
        return db.query("SELECT s.*,f.access_revision AS current_revision,f.visibility FROM file_shares s JOIN files f ON f.id=s.file_id WHERE s.file_id=? AND s.id=?",this::share,file,id).stream().findFirst().orElseThrow(FileShares::unavailable);
    }
    private Share share(java.sql.ResultSet r,int n) throws java.sql.SQLException {
        Instant end=r.getTimestamp("expires_at").toInstant();UUID id=r.getObject("id",UUID.class);
        String state=r.getTimestamp("revoked_at")!=null?"REVOKED":!end.isAfter(Instant.now())?"EXPIRED":r.getLong("access_revision")!=r.getLong("current_revision")||!r.getString("visibility").equals("PRIVATE")?"INVALIDATED":"ACTIVE";
        return new Share(id,r.getTimestamp("created_at").toInstant(),end,state,"/api/v1/files/shares/"+id+"/view");
    }
    private void lockFile(UUID file){db.execute("SET LOCAL lock_timeout='5s'");db.queryForList("SELECT id FROM files WHERE id=? FOR UPDATE",file);}
    private void audit(UUID file,UUID share,String actor,String action) {
        db.update("INSERT INTO file_audit(file_id,environment_id,actor,action,request_id,share_id) SELECT id,environment_id,?,?,?,? FROM files WHERE id=?",actor,action,MDC.get("requestId"),share,file);
    }
    private void acquire(){if(!hashing.tryAcquire())throw new FileFailure("FILE_SHARE_BUSY",503,"다른 비밀번호를 확인하고 있습니다. 잠시 후 다시 시도해 주세요.");}
    private static void validatePassword(String value){if(value==null||value.length()<8||value.length()>64||value.isBlank())throw new FileFailure("FILE_SHARE_INVALID_PASSWORD",400,"공유 비밀번호는 공백만으로 만들 수 없으며 8~64자로 입력해 주세요.");}
    static FileFailure unavailable(){return new FileFailure("FILE_SHARE_UNAVAILABLE",404,"사용할 수 없는 공유 링크입니다. 만료·철회 여부를 확인하거나 새 링크를 요청해 주세요.");}
}
