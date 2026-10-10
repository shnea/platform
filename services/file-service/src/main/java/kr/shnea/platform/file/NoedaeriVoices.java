package kr.shnea.platform.file;

import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Service
class NoedaeriVoices {
    private final NoedaeriClient client;
    private final FilesService files;
    private final FileStore store;
    private final JdbcTemplate db;
    private final JsonMapper json=new JsonMapper();
    NoedaeriVoices(NoedaeriClient client,FilesService files,FileStore store,JdbcTemplate db){this.client=client;this.files=files;this.store=store;this.db=db;}
    Object list(FileAccess.Context context) {
        configured();
        try {
            var profiles=client.voices(NoedaeriTaskContract.requester(context),context.projectId(),context.environmentId());
            if(!profiles.isArray()||profiles.size()>500)throw new IOException("Invalid voice list");
            var result=new ArrayList<JsonNode>();for(var profile:profiles){validate(profile,context,null);result.add(display(profile));}
            return result;
        }catch(Exception error){throw failure(error);}
    }
    JsonNode detail(FileAccess.Context context,UUID id) {
        configured();
        try{var profile=client.voice(id,NoedaeriTaskContract.requester(context),context.projectId(),context.environmentId());validate(profile,context,id);return display(profile);}
        catch(Exception error){throw failure(error);}
    }
    Object rename(FileAccess.Context context,UUID id,JsonNode body) {
        NoedaeriTaskContract.fields(body,"name");String name=NoedaeriTaskContract.string(body,"name",120,true);detail(context,id);
        try{var profile=client.renameVoice(id,NoedaeriTaskContract.requester(context),context.projectId(),context.environmentId(),name);validate(profile,context,id);audit(context,id,"voice.renamed");return display(profile);}
        catch(Exception error){throw failure(error);}
    }
    Object delete(FileAccess.Context context,UUID id,String confirmation) {synchronized(store.mediaMonitor){return deleteProfile(context,id,confirmation);}}
    private Object deleteProfile(FileAccess.Context context,UUID id,String confirmation) {
        if(!id.toString().equals(confirmation))throw new FileFailure("FILE_REQUEST_CONFLICT",409,"삭제할 목소리 ID를 확인해 주세요.");
        configured();
        try {
            JsonNode deleted;
            try{deleted=client.deleteVoice(id,NoedaeriTaskContract.requester(context),context.projectId(),context.environmentId());}
            catch(NoedaeriClient.RemoteFailure error){if(error.status!=404)throw error;deleted=json.readTree("{\"deleted\":true}");}
            if(!deleted.path("deleted").isBoolean())throw new IOException("Invalid voice deletion");
            if(!deleted.path("deleted").asBoolean()){audit(context,id,"voice.cleanup.failed");return Map.of("deleted",false,"status","cleanup_failed");}
            var cached=db.queryForList("SELECT file_id FROM file_noedaeri_voice_samples WHERE voice_id=? AND environment_id=? AND actor_id=? UNION SELECT (entry.value #>> '{}')::uuid FROM file_noedaeri_tasks task CROSS JOIN LATERAL jsonb_each(task.artifacts) entry WHERE task.voice_id=? AND task.environment_id=? AND task.actor_id=?",UUID.class,id,context.environmentId(),context.credentialId(),id,context.environmentId(),context.credentialId());
            for(UUID file:cached)try{files.delete(file,context);}catch(FileFailure error){if(error.status!=404)throw error;}
            db.update("UPDATE file_noedaeri_tasks SET state='DISCARDED',artifacts='{}'::jsonb,updated_at=now() WHERE voice_id=? AND environment_id=? AND actor_id=?",id,context.environmentId(),context.credentialId());
            db.update("DELETE FROM file_noedaeri_voice_samples WHERE voice_id=? AND environment_id=? AND actor_id=?",id,context.environmentId(),context.credentialId());
            audit(context,id,"voice.deleted");
            return Map.of("deleted",true);
        }catch(Exception error){throw failure(error);}
    }
    UUID sample(FileAccess.Context context,UUID id) {synchronized(store.mediaMonitor){return readSample(context,id);}}
    private UUID readSample(FileAccess.Context context,UUID id) {
        var profile=detail(context,id);
        if(!profile.path("kind").asString().equals("clone")||!profile.path("status").asString().equals("ready")||!profile.path("sample_available").asBoolean())throw FileFailure.missing();
        Path directory=null,output=null;
        try {
            for(UUID cached:db.queryForList("SELECT file_id FROM file_noedaeri_voice_samples WHERE voice_id=? AND environment_id=? AND actor_id=? ORDER BY created_at DESC",UUID.class,id,context.environmentId(),context.credentialId()))try{files.detail(cached,context);return cached;}catch(FileFailure error){if(error.status!=404)throw error;}
            directory=Files.createTempDirectory("platform-voice-");output=directory.resolve("reference.wav");
            client.voiceSample(id,NoedaeriTaskContract.requester(context),context.projectId(),context.environmentId(),output);
            byte[] bytes=Files.readAllBytes(output);NoedaeriTaskContract.validate("tts.voice.register","reference.wav",bytes);
            UUID stored=storeSample(context,id,bytes);
            return stored;
        }catch(Exception error){throw failure(error);}
        finally{try{if(output!=null)Files.deleteIfExists(output);if(directory!=null)Files.deleteIfExists(directory);}catch(IOException ignored){}}
    }
    UUID storeSample(FileAccess.Context context,UUID voice,byte[] bytes) throws Exception {
        String checksum=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        UUID request=UUID.randomUUID();
        var upload=files.createSource(context,new FilesService.Create(request,"reference.wav",(long)bytes.length,checksum,"PRIVATE","tmp"));
        for(long offset=upload.receivedBytes();offset<bytes.length;) {
            int count=(int)Math.min(upload.maxChunkBytes(),bytes.length-offset);byte[] chunk=Arrays.copyOfRange(bytes,(int)offset,(int)offset+count);
            String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(chunk));
            files.append(upload.uploadId(),context,offset,count,hash,new ByteArrayInputStream(chunk));offset+=count;
        }
        UUID stored=files.complete(upload.uploadId(),context).fileId();
        db.update("INSERT INTO file_noedaeri_voice_samples(environment_id,project_id,actor_id,voice_id,file_id) VALUES (?,?,?,?,?) ON CONFLICT DO NOTHING",context.environmentId(),context.projectId(),context.credentialId(),voice,stored);
        audit(context,voice,"voice.sample.stored");
        return stored;
    }
    static void validate(JsonNode profile,FileAccess.Context context,UUID id) throws IOException {
        UUID received=NoedaeriMedia.uuid(profile.path("id"));
        if(id!=null&&!id.equals(received)||!profile.path("requester_id").asString().equals(NoedaeriTaskContract.requester(context))
            ||!profile.path("project").asString().equals(context.projectId().toString())||!profile.path("environment").asString().equals(context.environmentId().toString())
            ||!Set.of("clone","preset").contains(profile.path("kind").asString())||!profile.path("name").isString()||profile.path("name").asString().isBlank()||profile.path("name").asString().length()>120)
            throw new IOException("Invalid voice scope");
    }
    static JsonNode summary(JsonNode profile) {
        var result=new JsonMapper().createObjectNode();
        for(String key:List.of("id","name","kind","speaker","status","registration_job_id","sample_bytes","sample_available","error_code","created_at"))if(profile.has(key))result.set(key,profile.path(key));
        return result;
    }
    private static JsonNode display(JsonNode profile) throws IOException {
        var result=(tools.jackson.databind.node.ObjectNode)summary(profile);var reference=profile.path("reference_text");
        if(!reference.isMissingNode()&&!reference.isNull()) {if(!reference.isString()||reference.asString().length()>1000)throw new IOException("Invalid reference text");result.set("reference_text",reference);}
        return result;
    }
    private FileFailure failure(Exception error) {
        if(error instanceof FileFailure failure)return failure;
        if(error instanceof NoedaeriClient.RemoteFailure remote&&remote.status==404)return FileFailure.missing();
        if(error instanceof NoedaeriClient.RemoteFailure remote&&remote.status==409)return new FileFailure("FILE_REQUEST_CONFLICT",409,"목소리가 사용 중이거나 같은 등록 요청의 내용이 다릅니다. 상태를 확인해 주세요.");
        return FileFailure.unavailable();
    }
    private void audit(FileAccess.Context context,UUID id,String action){db.update("INSERT INTO file_noedaeri_voice_audit(environment_id,actor,voice_id,action,request_id) VALUES (?,?,?,?,?)",context.environmentId(),context.actor(),id,action,org.slf4j.MDC.get("requestId"));}
    private void configured(){if(!client.configured())throw new FileFailure("FILE_MEDIA_NOT_CONFIGURED",503,"뇌대리 연결 설정이 필요합니다.");}
}
