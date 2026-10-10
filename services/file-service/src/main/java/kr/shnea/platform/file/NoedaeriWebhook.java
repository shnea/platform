package kr.shnea.platform.file;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@RestController
class NoedaeriWebhook {
    private final JdbcTemplate db;private final TransactionTemplate tx;private final NoedaeriClient client;
    private final JsonMapper json=new JsonMapper();
    NoedaeriWebhook(JdbcTemplate db,TransactionTemplate tx,NoedaeriClient client){this.db=db;this.tx=tx;this.client=client;}
    @PostMapping({"/api/webhooks/noedaeri","/api/v1/files/integrations/noedaeri/events"})
    Map<String,Object> receive(HttpServletRequest request) throws Exception {
        if(!client.configured())throw new FileFailure("FILE_MEDIA_NOT_CONFIGURED",503,"파일 변환 서비스 연결 설정이 필요합니다.");
        byte[] body=request.getInputStream().readNBytes(NoedaeriClient.JSON_LIMIT+1);
        if(body.length>NoedaeriClient.JSON_LIMIT)throw new FileFailure("FILE_MEDIA_EVENT_TOO_LARGE",413,"완료 알림 크기 제한을 초과했습니다.");
        verify(body,request.getHeader("X-Noedaeri-Timestamp"),request.getHeader("X-Noedaeri-Signature"),client.webhookSecret,Instant.now().getEpochSecond());
        JsonNode value;UUID event,job,key;
        try {
            value=json.readTree(body);event=NoedaeriMedia.uuid(value.path("event_id"));job=NoedaeriMedia.uuid(value.path("job_id"));key=NoedaeriMedia.uuid(value.path("idempotency_key"));
        }catch(Exception e){throw FileFailure.invalid();}
        String type=value.path("type").asString(),state=value.path("job").path("status").asString();
        if(value.path("version").asInt()!=1||!Set.of("job.succeeded","job.failed","job.cancelled").contains(type)
            ||!type.equals("job."+state)||!event.toString().equals(request.getHeader("X-Noedaeri-Event-ID")))throw FileFailure.invalid();
        String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        return tx.execute(s->{
            var expected=db.queryForList("SELECT job_id,kind FROM file_media_jobs WHERE request_id=?",key);
            if(expected.isEmpty()) {
                var tasks=db.queryForList("SELECT job_id,kind FROM file_noedaeri_tasks WHERE id=? FOR UPDATE",key);
                if(tasks.isEmpty())throw FileFailure.missing();var task=tasks.getFirst();
                if((task.get("job_id")!=null&&!job.equals(task.get("job_id")))||!value.path("job").path("kind").asString().equals(task.get("kind")))throw FileFailure.invalid();
                int inserted=db.update("INSERT INTO file_noedaeri_task_events(event_id,task_id,job_id,body_hash) VALUES (?,?,?,?) ON CONFLICT DO NOTHING",event,key,job,hash);
                if(inserted==0&&!hash.equals(db.queryForObject("SELECT body_hash FROM file_noedaeri_task_events WHERE event_id=?",String.class,event)))
                    throw new FileFailure("FILE_MEDIA_EVENT_CONFLICT",409,"같은 완료 알림 ID의 내용이 다릅니다.");
                db.update("UPDATE file_noedaeri_tasks SET job_id=coalesce(job_id,?),state=CASE WHEN state='NEW' THEN 'ACTIVE' ELSE state END,payload=NULL,next_check_at=now() WHERE id=?",job,key);
                return Map.of("accepted",true,"duplicate",inserted==0);
            }
            var row=expected.getFirst();
            if((row.get("job_id")!=null&&!job.equals(row.get("job_id")))||!value.path("job").path("kind").asString().equals(row.get("kind")))throw FileFailure.invalid();
            int inserted=db.update("INSERT INTO file_media_inbox(event_id,job_id,request_id,payload,body_hash) VALUES (?,?,?,?::jsonb,?) ON CONFLICT DO NOTHING",event,job,key,new String(body,StandardCharsets.UTF_8),hash);
            if(inserted==0&&!hash.equals(db.queryForObject("SELECT body_hash FROM file_media_inbox WHERE event_id=?",String.class,event)))
                throw new FileFailure("FILE_MEDIA_EVENT_CONFLICT",409,"같은 완료 알림 ID의 내용이 다릅니다.");
            return Map.of("accepted",true,"duplicate",inserted==0);
        });
    }
    static void verify(byte[] body,String timestamp,String signature,String secret,long now) throws Exception {
        boolean valid=false;
        if(timestamp!=null&&timestamp.matches("[0-9]{1,12}")&&signature!=null&&signature.matches("sha256=[0-9a-f]{64}")&&!secret.isBlank()) {
            long sent=Long.parseLong(timestamp);
            if(Math.abs(now-sent)<=300) {
                var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
                mac.update((timestamp+".").getBytes(StandardCharsets.US_ASCII));
                valid=MessageDigest.isEqual(mac.doFinal(body),HexFormat.of().parseHex(signature.substring(7)));
            }
        }
        if(!valid)throw new FileFailure("FILE_MEDIA_SIGNATURE_INVALID",401,"완료 알림 서명 또는 전송 시각을 확인해 주세요.");
    }
}
