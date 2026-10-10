package kr.shnea.platform.file;

import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.Connection;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Service
class NoedaeriTasks {
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final FilesService files;
    private final FileStore store;
    private final FileAccess access;
    private final NoedaeriClient client;
    private final JsonMapper json=new JsonMapper();
    NoedaeriTasks(JdbcTemplate db,TransactionTemplate tx,FilesService files,FileStore store,FileAccess access,NoedaeriClient client) {
        this.db=db;this.tx=tx;this.files=files;this.store=store;this.access=access;this.client=client;
    }
    Object services() {
        if(!client.configured())return Map.of("configured",false,"reachable",false,"services",List.of());
        try{return Map.of("configured",true,"reachable",true,"services",client.services());}
        catch(Exception error){return Map.of("configured",true,"reachable",false,"services",List.of(),"errorCode","FILE_SERVICE_UNAVAILABLE");}
    }
    Object create(FileAccess.Context context,JsonNode body) throws Exception {
        configured();NoedaeriTaskContract.fields(body,"requestId","sourceFileId","kind","input","options");
        UUID request=NoedaeriTaskContract.uuid(body.path("requestId"));String kind=NoedaeriTaskContract.string(body,"kind",32,true);
        if(!NoedaeriTaskContract.KINDS.contains(kind))throw FileFailure.invalid();
        boolean withoutFile=kind.equals("tts.synthesize")||kind.equals("tts.voice.register")&&body.path("input").path("kind").asString().equals("preset");
        UUID source=withoutFile?null:NoedaeriTaskContract.uuid(body.path("sourceFileId"));
        if(source==null&&!body.path("sourceFileId").isMissingNode())throw FileFailure.invalid();
        var info=source==null?null:files.detail(source,context);
        var input=new TreeMap<>(NoedaeriTaskContract.input(kind,body.path("input"),info,context));
        var options=new TreeMap<>(NoedaeriTaskContract.options(kind,body.path("options")));
        var payload=new TreeMap<String,Object>();payload.put("kind",kind);payload.put("input",input);payload.put("options",options);
        String encoded=json.writeValueAsString(payload);
        String fingerprint=hash(json.writeValueAsBytes(new TreeMap<>(Map.of("payload",payload,"source",source==null?"":source.toString()))));
        record Created(UUID id,boolean reused) {}
        Created accepted=tx.execute(status->{
            db.execute("SELECT pg_advisory_xact_lock(736452920)");
            var prior=db.queryForList("SELECT id,fingerprint FROM file_noedaeri_tasks WHERE environment_id=? AND actor_id=? AND request_id=?",context.environmentId(),context.credentialId(),request);
            if(!prior.isEmpty()) {
                if(!fingerprint.equals(prior.getFirst().get("fingerprint")))throw new FileFailure("FILE_REQUEST_CONFLICT",409,"같은 요청 ID의 입력·옵션이 다릅니다.");
                return new Created((UUID)prior.getFirst().get("id"),true);
            }
            if(db.queryForObject("SELECT count(*) FROM file_noedaeri_tasks WHERE state IN ('NEW','ACTIVE')",Integer.class)>=20)
                throw new FileFailure("FILE_QUOTA_EXCEEDED",409,"진행 중인 뇌대리 테스트 작업이 많습니다. 완료 후 다시 실행해 주세요.");
            if(source!=null){
                db.queryForList("SELECT id FROM files WHERE id=? FOR UPDATE",source);files.detail(source,context);
                if(kind.equals("tts.voice.register")) {
                    try{ReferenceAudio.validate(store.path(source));}catch(FileFailure error){throw error;}
                    catch(Exception error){throw FileFailure.unavailable();}
                }
            }
            UUID created=UUID.randomUUID();
            db.update("INSERT INTO file_noedaeri_tasks(id,environment_id,project_id,actor_id,request_id,source_file_id,kind,fingerprint,payload) VALUES (?,?,?,?,?,?,?,?,?::jsonb)",
                created,context.environmentId(),context.projectId(),context.credentialId(),request,source,kind,fingerprint,encoded);
            audit(context,created,"task.created");
            return new Created(created,false);
        });
        var result=detail(context,accepted.id(),false);result.put("reused",accepted.reused());
        return result;
    }
    List<Map<String,Object>> list(FileAccess.Context context) {
        return db.queryForList("SELECT id FROM file_noedaeri_tasks WHERE environment_id=? AND project_id=? AND actor_id=? ORDER BY created_at DESC LIMIT 100",context.environmentId(),context.projectId(),context.credentialId()).stream()
            .map(row->detail(context,(UUID)row.get("id"),false)).toList();
    }
    Map<String,Object> detail(FileAccess.Context context,UUID id,boolean contents) {
        var row=owned(context,id);var result=new LinkedHashMap<String,Object>();
        result.put("id",id);result.put("requestId",row.get("request_id"));result.put("kind",row.get("kind"));result.put("sourceFileId",row.get("source_file_id"));result.put("jobId",row.get("job_id"));result.put("voiceId",row.get("voice_id"));
        String state=(String)row.get("state");
        result.put("status",switch(state){case "NEW"->"pending";case "ACTIVE"->"running";case "IMPORTED"->"succeeded";case "CANCELLED","DISCARDED"->"cancelled";default->"failed";});
        result.put("remoteStatus",row.get("remote_status"));result.put("stage",row.get("stage"));result.put("errorCode",row.get("error_code"));result.put("cancelRequested",row.get("cancel_requested"));
        result.put("createdAt",instant(row.get("created_at")));result.put("updatedAt",instant(row.get("updated_at")));result.put("remoteExpiresAt",instant(row.get("remote_expires_at")));result.put("receiptAt",instant(row.get("receipt_at")));
        result.put("recoveryRequired",((Integer)row.get("attempts"))>=8&&Set.of("NEW","ACTIVE","IMPORTED").contains(state));
        result.put("manifest",node(row.get("manifest")));var artifacts=node(row.get("artifacts"));var available=new ArrayList<Object>();
        boolean sourceAvailable=true;
        if(row.get("source_file_id")!=null)try{result.put("sourceName",files.detail((UUID)row.get("source_file_id"),context).originalName());}catch(FileFailure failure){sourceAvailable=false;}
        if(state.equals("IMPORTED")&&sourceAvailable)for(String name:artifacts.propertyNames()) {
            UUID file=UUID.fromString(artifacts.path(name).asString());
            try {
                var info=files.detail(file,context);available.add(Map.of("name",name,"fileId",file,"bytes",info.size(),"retentionCode",info.retentionCode()));
                if(contents&&name.endsWith(".json")&&info.size()<=NoedaeriTaskContract.fileLimit(name))result.put("content",NoedaeriTaskContract.validate((String)row.get("kind"),name,Files.readAllBytes(store.path(file))));
            }catch(FileFailure ignored){}catch(Exception error){throw FileFailure.unavailable();}
        }
        result.put("artifacts",available);result.put("resultAvailable",!available.isEmpty());
        return result;
    }
    Object cancel(FileAccess.Context context,UUID id) {
        return tx.execute(status->{
            db.queryForList("SELECT id FROM file_noedaeri_tasks WHERE id=? FOR UPDATE",id);
            var row=owned(context,id);if(!Set.of("NEW","ACTIVE").contains(row.get("state")))throw new FileFailure("FILE_UPLOAD_CLOSED",409,"실행이 종료된 작업은 취소할 수 없습니다.");
            db.update("UPDATE file_noedaeri_tasks SET cancel_requested=true,attempts=0,next_check_at=now(),updated_at=now() WHERE id=?",id);
            if(!Boolean.TRUE.equals(row.get("cancel_requested")))audit(context,id,"task.cancel.requested");
            return detail(context,id,false);
        });
    }
    Object recover(FileAccess.Context context,UUID id) {
        var row=owned(context,id);
        if(row.get("state").equals("IMPORTED")&&row.get("job_id")==null)throw new FileFailure("FILE_UPLOAD_CLOSED",409,"즉시 등록된 프리셋은 재실행하지 않습니다. 목소리 목록을 조회하세요.");
        if(!Set.of("NEW","ACTIVE","IMPORTED").contains(row.get("state")))throw new FileFailure("FILE_UPLOAD_CLOSED",409,"종료 작업은 재실행하지 않습니다. 새 테스트에는 새 요청 ID를 사용하세요.");
        db.update("UPDATE file_noedaeri_tasks SET attempts=0,next_check_at=now(),updated_at=now() WHERE id=?",id);
        audit(context,id,"task.recovery.requested");
        return detail(context,id,false);
    }
    UUID artifact(FileAccess.Context context,UUID id,String name) {
        var row=owned(context,id);if(!row.get("state").equals("IMPORTED"))throw FileFailure.missing();
        if(row.get("source_file_id")!=null)files.detail((UUID)row.get("source_file_id"),context);
        var artifacts=node(row.get("artifacts"));if(!artifacts.has(name))throw FileFailure.missing();
        UUID file=UUID.fromString(artifacts.path(name).asString());files.detail(file,context);return file;
    }
    @Scheduled(fixedDelay=3000,initialDelay=20000)
    void work() {
        maintainResults();
        if(!client.configured())return;
        synchronized(store.mediaMonitor) {
            try(Connection connection=Objects.requireNonNull(db.getDataSource()).getConnection()) {
                try(var statement=connection.createStatement();var result=statement.executeQuery("SELECT pg_try_advisory_lock(736452921)")){result.next();if(!result.getBoolean(1))return;}
                try {
                    var due=db.queryForList("SELECT id FROM file_noedaeri_tasks WHERE state IN ('NEW','ACTIVE','IMPORTED') AND attempts<8 AND (state<>'IMPORTED' OR receipt_at IS NULL AND job_id IS NOT NULL) AND next_check_at<=now() ORDER BY next_check_at LIMIT 5",UUID.class);
                    for(UUID id:due)try{advance(id);}catch(Exception error){defer(id,error);}
                }finally{try(var statement=connection.createStatement()){statement.execute("SELECT pg_advisory_unlock(736452921)");}}
            }catch(Exception error){org.slf4j.LoggerFactory.getLogger(getClass()).warn("noedaeri_test_worker_unavailable");}
        }
    }
    void maintainResults() {
        db.update("UPDATE files result SET retention_code=source.retention_code,retention_marked_at=NULL FROM file_noedaeri_artifacts artifact JOIN files source ON source.id=artifact.source_file_id WHERE result.id=artifact.file_id AND result.state='READY' AND source.state='READY' AND result.retention_code<>source.retention_code");
        var rows=db.queryForList("SELECT task.* FROM file_noedaeri_tasks task JOIN files source ON source.id=task.source_file_id WHERE task.state<>'DISCARDED' AND source.state<>'READY' ORDER BY task.updated_at LIMIT 100");
        for(var row:rows) {
            UUID id=(UUID)row.get("id");var context=context(row);var artifacts=node(row.get("artifacts"));
            for(String name:artifacts.propertyNames())try{files.delete(UUID.fromString(artifacts.path(name).asString()),context);}catch(FileFailure failure){if(failure.status!=404)throw failure;}
            if(Set.of("NEW","ACTIVE").contains(row.get("state")))db.update("UPDATE file_noedaeri_tasks SET cancel_requested=true,artifacts='{}'::jsonb,next_check_at=now(),attempts=CASE WHEN cancel_requested THEN attempts ELSE 0 END WHERE id=?",id);
            else db.update("UPDATE file_noedaeri_tasks SET state='DISCARDED',artifacts='{}'::jsonb,updated_at=now() WHERE id=?",id);
        }
    }
    void advance(UUID id) throws Exception {
        var row=db.queryForMap("SELECT * FROM file_noedaeri_tasks WHERE id=?",id);var context=context(row);access.requireActive(context.environmentId());
        UUID source=(UUID)row.get("source_file_id"),remote=(UUID)row.get("job_id");boolean cancel=(Boolean)row.get("cancel_requested");
        if(source!=null)try{files.detail(source,context);}catch(FileFailure failure){if(failure.status!=404)throw failure;cancel=true;db.update("UPDATE file_noedaeri_tasks SET cancel_requested=true WHERE id=?",id);}
        if(remote==null) {
            if(cancel&&row.get("creation_started_at")==null){db.update("UPDATE file_noedaeri_tasks SET state='CANCELLED',payload=NULL,updated_at=now() WHERE id=?",id);return;}
            var payload=node(row.get("payload"));String kind=payload.path("kind").asString();
            db.update("UPDATE file_noedaeri_tasks SET creation_started_at=coalesce(creation_started_at,now()) WHERE id=?",id);
            if(cancel&&kind.equals("tts.synthesize")) {
                remote=client.findJob(id);if(remote==null)throw new IOException("Unconfirmed TTS creation");
            }else if(kind.equals("tts.voice.register")) {
                var input=json.convertValue(payload.path("input"),Map.class);input.put("idempotency_key",id.toString());
                var profile=client.registerVoice(input);NoedaeriVoices.validate(profile,context,null);if(!profile.path("kind").asString().equals(payload.path("input").path("kind").asString()))throw new IOException("Voice kind mismatch");UUID voice=NoedaeriMedia.uuid(profile.path("id"));
                if(profile.path("kind").asString().equals("preset")) {
                    if(!profile.path("status").asString().equals("ready"))throw new IOException("Invalid preset status");
                    db.update("UPDATE file_noedaeri_tasks SET voice_id=?,state='IMPORTED',payload=NULL,manifest=?::jsonb,attempts=0,error_code=NULL,updated_at=now() WHERE id=?",voice,json.writeValueAsString(NoedaeriVoices.summary(profile)),id);return;
                }
                remote=NoedaeriMedia.uuid(profile.path("registration_job_id"));db.update("UPDATE file_noedaeri_tasks SET voice_id=? WHERE id=?",voice,id);
            }else {
                var created=client.create(id,kind,json.convertValue(payload.path("input"),Map.class),json.convertValue(payload.path("options"),Map.class));remote=NoedaeriMedia.uuid(created.path("id"));
            }
            db.update("UPDATE file_noedaeri_tasks SET job_id=?,state='ACTIVE',payload=NULL,updated_at=now() WHERE id=?",remote,id);
        }
        cancel=cancel||Boolean.TRUE.equals(db.queryForObject("SELECT cancel_requested FROM file_noedaeri_tasks WHERE id=?",Boolean.class,id));
        if(row.get("state").equals("IMPORTED")) {
            if(cancel){db.update("UPDATE file_noedaeri_tasks SET state='DISCARDED',updated_at=now() WHERE id=?",id);return;}
            for(String name:node(row.get("artifacts")).propertyNames())files.detail(NoedaeriMedia.uuid(node(row.get("artifacts")).path(name)),context);
            if(remote!=null)client.receipt(remote,(UUID)row.get("event_id"));db.update("UPDATE file_noedaeri_tasks SET receipt_at=now(),attempts=0,updated_at=now() WHERE id=?",id);return;
        }
        var status=client.status(remote);
        if(!NoedaeriMedia.uuid(status.path("id")).equals(remote)||!status.path("kind").asString().equals(row.get("kind")))throw new IOException("Invalid task status");
        String state=status.path("status").asString();
        db.update("UPDATE file_noedaeri_tasks SET remote_status=?,stage=?,updated_at=now() WHERE id=?",state,limited(status.path("stage"),80),id);
        if(Set.of("failed","cancelled").contains(state)) {
            db.update("UPDATE file_noedaeri_tasks SET state=?,payload=NULL,error_code=?,updated_at=now() WHERE id=?",state.equals("cancelled")?"CANCELLED":"FAILED",limited(status.path("error_code"),100),id);return;
        }
        if(cancel) {
            if(state.equals("succeeded")){db.update("UPDATE file_noedaeri_tasks SET state='DISCARDED',payload=NULL,updated_at=now() WHERE id=?",id);return;}
            client.cancel(remote);db.update("UPDATE file_noedaeri_tasks SET next_check_at=now()+interval '30 seconds',attempts=0 WHERE id=?",id);return;
        }
        if(state.equals("uploading")){if(source==null)throw new IOException("Unexpected upload");client.upload(remote,store.path(source));db.update("UPDATE file_noedaeri_tasks SET next_check_at=now()+interval '5 seconds',attempts=0 WHERE id=?",id);return;}
        if(Set.of("queued","running","processing","interrupted").contains(state)){db.update("UPDATE file_noedaeri_tasks SET next_check_at=now()+interval '5 minutes',attempts=0 WHERE id=?",id);return;}
        if(!state.equals("succeeded"))throw new IOException("Unknown task status");
        String kind=(String)row.get("kind");boolean voiceRegistration=kind.equals("tts.voice.register");
        UUID event=NoedaeriMedia.uuid(status.path("terminal_event_id"));Instant expires=voiceRegistration&&(status.path("expires_at").isNull()||status.path("expires_at").isMissingNode())?null:Instant.parse(status.path("expires_at").asString());
        if(db.queryForObject("SELECT count(*) FROM file_noedaeri_task_events WHERE task_id=? AND (event_id<>? OR job_id<>?)",Integer.class,id,event,remote)>0)throw new IOException("Terminal event mismatch");
        if(!voiceRegistration&&!expires.isAfter(Instant.now()))throw new IOException("Expired remote result");
        var manifest=status.path("result");UUID voice=null;
        if(voiceRegistration) {
            if(!manifest.path("type").asString().equals("voice_profile"))throw new IOException("Invalid voice result");voice=NoedaeriMedia.uuid(manifest.path("voice_id"));
            var known=db.queryForObject("SELECT voice_id FROM file_noedaeri_tasks WHERE id=?",UUID.class,id);
            if(known!=null&&!known.equals(voice))throw new IOException("Voice ID mismatch");
            var profile=client.voice(voice,NoedaeriTaskContract.requester(context),context.projectId(),context.environmentId());NoedaeriVoices.validate(profile,context,voice);
            if(!profile.path("kind").asString().equals("clone")||!profile.path("status").asString().equals("ready")||!profile.path("sample_available").asBoolean())throw new IOException("Voice not ready");
            var combined=json.createObjectNode();combined.put("type","voice_profile");combined.put("voice_id",voice.toString());combined.set("profile",NoedaeriVoices.summary(profile));manifest=combined;
            db.update("UPDATE file_noedaeri_tasks SET voice_id=? WHERE id=?",voice,id);
        }else NoedaeriTaskContract.manifest(kind,manifest);
        var output=json.createObjectNode();
        for(String name:NoedaeriTaskContract.files(kind)) {
            Path directory=Files.createTempDirectory("platform-noedaeri-");Path temporary=directory.resolve(name);
            try {
                if(voiceRegistration)client.voiceSample(voice,NoedaeriTaskContract.requester(context),context.projectId(),context.environmentId(),temporary);
                else if(Set.of("tts.synthesize","video.thumbnail").contains(kind)||name.endsWith(".zip"))client.downloadResult(remote,temporary,NoedaeriTaskContract.fileLimit(name));
                else client.downloadTaskFile(remote,kind,name,temporary);
                byte[] bytes=Files.readAllBytes(temporary);NoedaeriTaskContract.validate(kind,name,bytes);
                long declared=manifest.path("file_sizes").path(name).asLong(-1);if(declared>=0&&declared!=bytes.length)throw new IOException("Artifact size mismatch");
                UUID key=UUID.nameUUIDFromBytes((id+":"+name).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                String retention=source==null?"tmp":files.detail(source,context).retentionCode();
                var upload=files.createSource(context,new FilesService.Create(key,name,(long)bytes.length,hash(bytes),"PRIVATE",retention));
                for(long offset=upload.receivedBytes();offset<bytes.length;) {
                    int count=(int)Math.min(upload.maxChunkBytes(),bytes.length-offset);byte[] chunk=Arrays.copyOfRange(bytes,(int)offset,(int)offset+count);
                    files.append(upload.uploadId(),context,offset,count,hash(chunk),new ByteArrayInputStream(chunk));offset+=count;
                }
                var stored=files.complete(upload.uploadId(),context);output.put(name,stored.fileId().toString());
                db.update("INSERT INTO file_noedaeri_artifacts(file_id,task_id,source_file_id) VALUES (?,?,?) ON CONFLICT DO NOTHING",stored.fileId(),id,source);
                db.update("UPDATE file_noedaeri_tasks SET artifacts=?::jsonb WHERE id=?",json.writeValueAsString(output),id);
            }finally{Files.deleteIfExists(temporary);Files.deleteIfExists(directory);}
        }
        UUID remoteId=remote;var savedManifest=manifest;
        tx.executeWithoutResult(transaction->{
            if(source!=null){db.queryForList("SELECT id FROM files WHERE id=? FOR UPDATE",source);files.detail(source,context);}
            db.update("UPDATE file_noedaeri_tasks SET state='IMPORTED',manifest=?::jsonb,artifacts=?::jsonb,event_id=?,remote_expires_at=?,attempts=0,error_code=NULL,next_check_at=now(),updated_at=now() WHERE id=?",
                json.writeValueAsString(savedManifest),json.writeValueAsString(output),event,expires==null?null:java.sql.Timestamp.from(expires),id);
        });
        client.receipt(remoteId,event);db.update("UPDATE file_noedaeri_tasks SET receipt_at=now(),updated_at=now() WHERE id=?",id);
    }
    private void defer(UUID id,Exception error) {
        String code=error instanceof NoedaeriClient.RemoteFailure remote&&!remote.code.isBlank()?remote.code:"FILE_SERVICE_UNAVAILABLE";
        db.update("UPDATE file_noedaeri_tasks SET attempts=attempts+1,error_code=?,next_check_at=now()+interval '30 seconds'*power(2,least(attempts,6)),updated_at=now() WHERE id=?",code,id);
        if(error instanceof NoedaeriClient.RemoteFailure remote&&remote.status>=400&&remote.status<500&&remote.status!=429)
            db.update("UPDATE file_noedaeri_tasks SET state='FAILED',payload=NULL WHERE id=? AND state<>'IMPORTED'",id);
    }
    private Map<String,Object> owned(FileAccess.Context context,UUID id) {
        var rows=db.queryForList("SELECT * FROM file_noedaeri_tasks WHERE id=? AND environment_id=? AND project_id=? AND actor_id=?",id,context.environmentId(),context.projectId(),context.credentialId());
        if(rows.isEmpty())throw FileFailure.missing();return rows.getFirst();
    }
    private void audit(FileAccess.Context context,UUID id,String action) {
        db.update("INSERT INTO file_noedaeri_task_audit(task_id,environment_id,actor,action,request_id) VALUES (?,?,?,?,?)",id,context.environmentId(),context.actor(),action,org.slf4j.MDC.get("requestId"));
    }
    private FileAccess.Context context(Map<String,Object> row) {return new FileAccess.Context((UUID)row.get("project_id"),(UUID)row.get("environment_id"),(UUID)row.get("actor_id"),"ADMIN");}
    private Instant instant(Object value){return value==null?null:((java.sql.Timestamp)value).toInstant();}
    private JsonNode node(Object value){return value==null?json.createObjectNode():json.readTree(value.toString());}
    private String limited(JsonNode value,int limit){String text=value.asString("");return text.matches("[A-Za-z0-9_.-]{1,"+limit+"}")?text:null;}
    private static String hash(byte[] bytes) throws Exception {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    private void configured(){if(!client.configured())throw new FileFailure("FILE_MEDIA_NOT_CONFIGURED",503,"뇌대리 연결 설정이 필요합니다.");}
}
