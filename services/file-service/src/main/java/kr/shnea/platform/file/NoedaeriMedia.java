package kr.shnea.platform.file;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
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
class NoedaeriMedia {
    static final Set<String> IMAGES=Set.of("png","jpg","jpeg","jfif","gif","webp","bmp","ico","tif","tiff","heic","heif","avif");
    record Job(UUID request,UUID file,String kind,UUID generation,UUID remote,String state,UUID event,int attempts) {
        boolean video(){return kind.equals("video.package");}
    }
    record Artifact(String name,long bytes) {}
    record Result(List<Artifact> artifacts,String mime,List<FileVideos.Variant> variants,double duration,FileVideos.Subtitles subtitles) {}
    private final JdbcTemplate db;private final TransactionTemplate tx;private final FileStore store;
    private final FilesService files;private final FileVideos videos;private final FileAccess access;
    private final NoedaeriClient client;private final MediaBackend backend;private final JsonMapper json=new JsonMapper();
    NoedaeriMedia(JdbcTemplate db,TransactionTemplate tx,FileStore store,FilesService files,FileVideos videos,FileAccess access,NoedaeriClient client,MediaBackend backend) {
        this.db=db;this.tx=tx;this.store=store;this.files=files;this.videos=videos;this.access=access;this.client=client;this.backend=backend;
    }
    @Scheduled(fixedDelay=3000,initialDelay=20000)
    void work() {
        synchronized(store.mediaMonitor) {
            try(Connection connection=Objects.requireNonNull(db.getDataSource()).getConnection()) {
                try(var statement=connection.createStatement();var result=statement.executeQuery("SELECT pg_try_advisory_lock(736452919)")) {
                    result.next();if(!result.getBoolean(1))return;
                }
                try {
                    if(!client.configured()) {
                        if(backend.remote()) {
                            discover();
                            db.update("UPDATE file_views v SET error_code='FILE_MEDIA_NOT_CONFIGURED' FROM files f WHERE f.id=v.file_id AND v.state='QUEUED' AND lower(f.original_name) ~ '\\.(png|jpg|jpeg|jfif|gif|webp|bmp|ico|tif|tiff|heic|heif|avif|mp4|m4v|mov|mkv|webm)$'");
                            db.update("UPDATE file_videos SET error_code='FILE_MEDIA_NOT_CONFIGURED' WHERE state='QUEUED'");
                        }
                        return;
                    }
                    if(backend.remote()){videos.recover();discover();claim();}
                    var pending=db.query("""
                        SELECT j.* FROM file_media_jobs j WHERE j.state IN ('NEW','ACTIVE','IMPORTED')
                        AND (j.state<>'IMPORTED' OR j.receipt_at IS NULL)
                        AND (j.next_check_at<=now() OR EXISTS(SELECT 1 FROM file_media_inbox i WHERE i.request_id=j.request_id AND i.processed_at IS NULL))
                        ORDER BY CASE WHEN j.state='IMPORTED' THEN 0 ELSE 1 END,j.next_check_at LIMIT 10
                        """,(r,n)->new Job(r.getObject("request_id",UUID.class),r.getObject("file_id",UUID.class),r.getString("kind"),
                        r.getObject("generation",UUID.class),r.getObject("job_id",UUID.class),r.getString("state"),r.getObject("event_id",UUID.class),r.getInt("attempts")));
                    for(Job job:pending)try{advance(job);}catch(Exception e){defer(job,e);}
                }finally{try(var statement=connection.createStatement()){statement.execute("SELECT pg_advisory_unlock(736452919)");}}
            }catch(Exception e){org.slf4j.LoggerFactory.getLogger(getClass()).warn("noedaeri_media_worker_unavailable");}
        }
    }
    void discover() {
        db.update("INSERT INTO file_views(file_id) SELECT f.id FROM files f LEFT JOIN file_views v ON v.file_id=f.id WHERE f.state='READY' AND v.file_id IS NULL ORDER BY f.completed_at LIMIT 100 ON CONFLICT DO NOTHING");
        db.update("INSERT INTO file_videos(file_id) SELECT f.id FROM files f LEFT JOIN file_videos v ON v.file_id=f.id WHERE f.state='READY' AND v.file_id IS NULL AND lower(f.original_name) ~ '\\.(mp4|m4v|mov|mkv|webm)$' ORDER BY f.completed_at LIMIT 100 ON CONFLICT DO NOTHING");
    }
    void claim() {
        if(db.queryForObject("SELECT count(*) FROM file_media_jobs WHERE state IN ('NEW','ACTIVE')",Integer.class)>=16)return;
        tx.executeWithoutResult(s->{
            var candidates=db.queryForList("""
                SELECT f.id FROM files f JOIN file_views v ON v.file_id=f.id LEFT JOIN file_videos h ON h.file_id=f.id
                WHERE f.state='READY' AND ((v.state='QUEUED' AND lower(f.original_name) ~ '\\.(png|jpg|jpeg|jfif|gif|webp|bmp|ico|tif|tiff|heic|heif|avif)$')
                OR (h.state='QUEUED' AND v.state<>'PROCESSING')) ORDER BY f.completed_at LIMIT 1 FOR UPDATE OF f SKIP LOCKED
                """,UUID.class);
            if(candidates.isEmpty())return;
            UUID id=candidates.getFirst();var row=files.downloadable(id);access.requireActive(row.environment());
            boolean video=FileVideos.candidate(row.name());long limit=video?client.videoInputLimit:32_000_000;
            if(row.size()>limit) {
                String table=video?"file_videos":"file_views";
                db.update("UPDATE "+table+" SET state='UNSUPPORTED',error_code='FILE_MEDIA_INPUT_LIMIT',finished_at=now() WHERE file_id=?",id);
                if(video)db.update("UPDATE file_views SET state='UNSUPPORTED',kind='VIDEO',error_code='FILE_MEDIA_INPUT_LIMIT',finished_at=now() WHERE file_id=? AND state='QUEUED'",id);
                return;
            }
            UUID generation=UUID.randomUUID(),request=UUID.randomUUID();
            if(video) {
                db.update("UPDATE file_videos SET state='PROCESSING',processing_backend='noedaeri',generation=?,progress=0,attempts=attempts+1,started_at=now(),heartbeat_at=now(),error_code=NULL WHERE file_id=?",generation,id);
                db.update("UPDATE file_views SET state='PROCESSING',processing_backend='noedaeri',processing_generation=?,kind='VIDEO',started_at=now(),error_code=NULL WHERE file_id=?",generation,id);
            }else db.update("UPDATE file_views SET state='PROCESSING',processing_backend='noedaeri',processing_generation=?,kind='IMAGE',attempts=attempts+1,started_at=now(),error_code=NULL WHERE file_id=?",generation,id);
            db.update("INSERT INTO file_media_jobs(request_id,file_id,kind,generation,options) SELECT ?,id,?,?,video_options FROM files WHERE id=?",request,video?"video.package":"image.package",generation,id);
        });
    }
    boolean current(Job job) {
        String table=job.video()?"file_videos":"file_views",column=job.video()?"generation":"processing_generation";
        return db.queryForObject("SELECT count(*) FROM "+table+" v JOIN files f ON f.id=v.file_id WHERE f.id=? AND f.state='READY' AND v.state='PROCESSING' AND v.processing_backend='noedaeri' AND v."+column+"=?",Integer.class,job.file(),job.generation())==1;
    }
    void advance(Job job) throws Exception {
        if(job.state().equals("IMPORTED")) {
            client.receipt(job.remote(),job.event());
            db.update("UPDATE file_media_jobs SET receipt_at=now(),error_code=NULL,updated_at=now() WHERE request_id=? AND state='IMPORTED'",job.request());return;
        }
        if(!current(job)) {
            if(job.remote()!=null)client.cancel(job.remote());
            db.update("UPDATE file_media_jobs SET state='DISCARDED',updated_at=now() WHERE request_id=?",job.request());
            processed(job);return;
        }
        var row=files.downloadable(job.file());access.requireActive(row.environment());
        UUID remote=job.remote();
        if(remote==null) {
            var options=VideoOptions.decode(db.queryForObject("SELECT options::text FROM file_media_jobs WHERE request_id=?",String.class,job.request()));
            var accepted=client.create(job.request(),job.kind(),extension(row.name()),options==null?Map.of():options.upstream());validateIdentity(job,accepted,null);
            remote=uuid(accepted.path("id"));
            db.update("UPDATE file_media_jobs SET job_id=?,state='ACTIVE',updated_at=now() WHERE request_id=? AND job_id IS NULL",remote,job.request());
        }
        var status=client.status(remote);validateIdentity(job,status,remote);
        String state=status.path("status").asString();
        if(state.equals("uploading")) {
            client.upload(remote,store.path(job.file()));
            schedule(job,30);return;
        }
        if(Set.of("queued","running","processing","interrupted").contains(state)) {
            if(job.video())db.update("UPDATE file_videos SET heartbeat_at=now() WHERE file_id=? AND generation=?",job.file(),job.generation());
            processed(job);schedule(job,300);return;
        }
        UUID event=uuid(status.path("terminal_event_id"));
        db.update("UPDATE file_media_jobs SET event_id=? WHERE request_id=?",event,job.request());
        if(Set.of("failed","cancelled").contains(state)) {
            String remoteCode=status.path("error_code").asString("");
            String code=Set.of("unsupported_media","invalid_media_or_conversion_failed").contains(remoteCode)?"FILE_MEDIA_UNSUPPORTED":"FILE_MEDIA_REMOTE_FAILED";
            finishFailure(job,code,code.equals("FILE_MEDIA_UNSUPPORTED")?"UNSUPPORTED":"FAILED");return;
        }
        if(!state.equals("succeeded"))throw new IOException("Unexpected job state");
        Instant expiry=Instant.parse(status.path("expires_at").asString());
        if(!expiry.isAfter(Instant.now()))throw new NoedaeriClient.RemoteFailure(410);
        Result result=manifest(job,status.path("result"));
        importResult(job,remote,event,row,result);
    }
    private void validateIdentity(Job job,JsonNode value,UUID remote) throws IOException {
        if(remote!=null&&!uuid(value.path("id")).equals(remote))throw new IOException("Job identity mismatch");
        if(!value.path("kind").asString().equals(job.kind()))throw new IOException("Job kind mismatch");
        if(value.has("idempotency_key")&&!uuid(value.path("idempotency_key")).equals(job.request()))throw new IOException("Request identity mismatch");
    }
    Result manifest(Job job,JsonNode result) throws IOException {
        if(!result.path("type").asString().equals(job.video()?"video_package":"image_package"))throw new IOException("Invalid result type");
        var options=VideoOptions.decode(db.queryForObject("SELECT options::text FROM file_media_jobs WHERE request_id=?",String.class,job.request()));
        var requested=options==null?null:options.subtitles();
        FileVideos.Subtitles subtitles=null;
        var caption=result.path("subtitles");
        if(caption.isObject()) {
            if(!job.video()||requested==null||!caption.path("mode").asString().equals(requested.mode())
                ||!VideoOptions.LANGUAGES.contains(caption.path("language").asString())
                ||!requested.language().equals("auto")&&!caption.path("language").asString().equals(requested.language())
                ||!caption.path("timing").asString().equals("vad_proportional")||!caption.path("cue_count").isIntegralNumber()
                ||caption.path("cue_count").asLong()<0||caption.path("cue_count").asLong()>10000
                ||!caption.path("srt").asString().equals("subtitles.srt")||!caption.path("vtt").asString().equals("subtitles.vtt")
                ||!caption.path("transcript").asString().equals("transcript.json"))throw new IOException("Invalid subtitle manifest");
            subtitles=new FileVideos.Subtitles(requested.mode(),caption.path("language").asString(),"vad_proportional",caption.path("cue_count").asInt(),"subtitles.srt","subtitles.vtt","transcript.json");
        }else if(requested!=null||!caption.isMissingNode()&&!caption.isNull())throw new IOException("Missing subtitle result");
        var listed=new LinkedHashSet<String>();
        if(!result.path("files").isArray())throw new IOException("Missing manifest");
        for(var value:result.path("files")) {
            String name=value.asString();if(!listed.add(name)||listed.size()>20000)throw new IOException("Invalid manifest");
            if(VideoOptions.ARTIFACTS.contains(name)) {if(subtitles==null)throw new IOException("Unexpected subtitle artifact");}
            else if(!name.matches("thumbnail\\.jpg|preview\\.webp|metadata\\.json|image\\.zip|video\\.zip|master\\.m3u8|"+FileVideos.HLS_CHILD_PATTERN))throw new IOException("Invalid manifest path");
        }
        if(subtitles!=null&&!listed.containsAll(VideoOptions.ARTIFACTS))throw new IOException("Incomplete subtitle result");
        var artifacts=new ArrayList<Artifact>();var variants=new ArrayList<FileVideos.Variant>();String mime;
        double duration=0;
        if(job.video()) {
            if(!result.path("master").asString().equals("master.m3u8")||!result.path("thumbnail").asString().equals("thumbnail.jpg"))throw new IOException("Invalid video manifest");
            duration=result.path("duration_seconds").asDouble();if(!Double.isFinite(duration)||duration<=0||duration>3600)throw new IOException("Invalid duration");
            for(var v:result.path("variants")) {
                String label=v.path("label").asString(),playlist=v.path("playlist").asString();
                if(!label.matches("[0-9]{1,4}p")||!playlist.matches("(?:q[0-9]{1,4}|[0-9]{1,4}p)\\.m3u8")||!listed.contains(playlist))throw new IOException("Invalid rendition");
                int quality=Integer.parseInt(label.substring(0,label.length()-1)),w=v.path("width").asInt(),h=v.path("height").asInt(),bandwidth=v.path("bandwidth").asInt();
                if(quality<2||quality>1080||w<2||h<2||w>4096||h>4096||(long)w*h>8_500_000||bandwidth<1)throw new IOException("Invalid rendition limits");
                if(variants.stream().anyMatch(old->old.quality()==quality||old.playlist().equals(playlist)))throw new IOException("Duplicate rendition");
                variants.add(new FileVideos.Variant(quality,w,h,bandwidth,playlist));
            }
            if(variants.isEmpty()||variants.size()>3||!listed.containsAll(List.of("master.m3u8","thumbnail.jpg")))throw new IOException("Incomplete video result");
            for(String name:listed)if(name.equals("thumbnail.jpg")||name.equals("master.m3u8")||name.matches(FileVideos.HLS_CHILD_PATTERN)||VideoOptions.ARTIFACTS.contains(name)) {
                if(!result.path("file_sizes").path(name).isIntegralNumber())throw new IOException("Missing artifact size");
                artifacts.add(artifact(name,result.path("file_sizes").path(name).asLong()));
            }
            mime="video/mp4";
        }else {
            var source=result.path("source");mime=source.path("media_type").asString();
            if(!Set.of("image/jpeg","image/png","image/gif","image/webp","image/bmp","image/x-icon","image/vnd.microsoft.icon","image/tiff","image/heic","image/heif","image/avif").contains(mime))throw new IOException("Invalid image media type");
            int w=source.path("width").asInt(),h=source.path("height").asInt();
            if(w<1||h<1||w>10000||h>10000||(long)w*h>40_000_000)throw new IOException("Invalid image dimensions");
            for(String name:List.of("thumbnail.jpg","preview.webp")) {
                JsonNode info=result.path(name.equals("thumbnail.jpg")?"thumbnail":"preview");
                int aw=info.path("width").asInt(),ah=info.path("height").asInt();
                if(!listed.contains(name)||!info.path("name").asString().equals(name)||aw<1||ah<1||aw>w||ah>h
                    ||aw>(name.equals("thumbnail.jpg")?480:1600)||ah>(name.equals("thumbnail.jpg")?320:1600)
                    ||!info.path("media_type").asString().equals(name.equals("thumbnail.jpg")?"image/jpeg":"image/webp"))throw new IOException("Invalid image result");
                artifacts.add(artifact(name,info.path("bytes").asLong()));
            }
        }
        return new Result(List.copyOf(artifacts),mime,List.copyOf(variants),duration,subtitles);
    }
    private static Artifact artifact(String name,long bytes) throws IOException {
        long cap=VideoOptions.ARTIFACTS.contains(name)?16*1024*1024:name.endsWith(".ts")?16*1024*1024:name.endsWith(".m3u8")?262144:2_000_000;
        long minimum=Set.of("subtitles.srt","transcript.txt").contains(name)?0:1;
        if(bytes<minimum||bytes>cap)throw new IOException("Invalid artifact size");return new Artifact(name,bytes);
    }
    void importResult(Job job,UUID remote,UUID event,FilesService.Row row,Result result) throws Exception {
        long bytes=result.artifacts().stream().mapToLong(Artifact::bytes).sum();
        Path destination=store.mediaOutput(job.file(),job.generation(),job.video()),stage=destination.resolveSibling(job.generation()+".tmp");
        boolean imported=false;
        try {
            if(!current(job))throw FileFailure.missing();
            videos.reserve(row,bytes);purge(stage);purge(destination);Files.createDirectories(stage);
            for(Artifact artifact:result.artifacts()) {
                client.download(remote,artifact.name(),stage.resolve(artifact.name()),artifact.bytes());
                if(Files.size(stage.resolve(artifact.name()))!=artifact.bytes())throw new IOException("Artifact size mismatch");
                if(store.usableSpace()<bytes)throw new IOException("Media storage exhausted");
            }
            validateFiles(job,result,stage);
            access.requireActive(row.environment());
            tx.executeWithoutResult(s->{
                db.queryForList("SELECT id FROM files WHERE id=? FOR UPDATE",job.file());files.downloadable(job.file());
                if(!current(job)||db.queryForObject("SELECT count(*) FROM file_media_jobs WHERE request_id=? AND job_id=? AND state='ACTIVE'",Integer.class,job.request(),remote)!=1)throw FileFailure.missing();
                try{Files.move(stage,destination,StandardCopyOption.ATOMIC_MOVE);}catch(IOException e){throw FileFailure.unavailable();}
                String mime=job.video()?switch(extension(row.name())){case "webm"->"video/webm";case "mov"->"video/quicktime";case "mkv"->"video/x-matroska";default->"video/mp4";}:result.mime();
                db.update("UPDATE file_views SET state='READY',kind=?,media_type=?,thumbnail=true,media_generation=?,error_code=NULL,finished_at=now() WHERE file_id=? AND processing_generation=?",job.video()?"VIDEO":"IMAGE",mime,job.generation(),job.file(),job.generation());
                if(job.video())db.update("UPDATE file_videos SET state='READY',progress=100,duration_seconds=?,variants=?::jsonb,subtitles=?::jsonb,error_code=NULL,finished_at=now() WHERE file_id=? AND generation=?",result.duration(),json.writeValueAsString(result.variants()),result.subtitles()==null?null:json.writeValueAsString(result.subtitles()),job.file(),job.generation());
                db.update("UPDATE files SET video_bytes=?,video_reserved_bytes=0 WHERE id=?",bytes,job.file());
                db.update("UPDATE file_media_jobs SET state='IMPORTED',event_id=?,attempts=0,error_code=NULL,next_check_at=now(),updated_at=now() WHERE request_id=?",event,job.request());
                db.update("INSERT INTO file_audit(file_id,environment_id,actor,action) VALUES (?,?,'system:noedaeri',?)",job.file(),row.environment(),job.video()?"file.video.ready":"file.preview.ready");
                processed(job);
            });imported=true;
        }finally {
            purge(stage);
            if(!imported){purge(destination);if(current(job))db.update("UPDATE files SET video_reserved_bytes=0 WHERE id=?",job.file());}
        }
    }
    private void validateFiles(Job job,Result result,Path stage) throws IOException {
        byte[] thumbnail;try(var in=Files.newInputStream(stage.resolve("thumbnail.jpg"))){thumbnail=in.readNBytes(3);}
        if(thumbnail.length<3||thumbnail[0]!=(byte)0xff||thumbnail[1]!=(byte)0xd8||thumbnail[2]!=(byte)0xff)throw new IOException("Invalid JPEG result");
        if(!job.video()) {
            byte[] head;try(var in=Files.newInputStream(stage.resolve("preview.webp"))){head=in.readNBytes(12);}
            if(head.length<12||!new String(head,0,4,StandardCharsets.US_ASCII).equals("RIFF")||!new String(head,8,4,StandardCharsets.US_ASCII).equals("WEBP"))throw new IOException("Invalid WebP result");return;
        }
        if(result.subtitles()!=null) {
            for(String name:VideoOptions.ARTIFACTS) {
                String text=Files.readString(stage.resolve(name),StandardCharsets.UTF_8);
                if(name.equals("subtitles.vtt")&&!text.matches("(?s)\\ufeff?WEBVTT(?:[ \\t].*)?(?:\\r?\\n.*)?"))throw new IOException("Invalid WebVTT result");
                if(name.equals("transcript.json")&&!json.readTree(text).isObject())throw new IOException("Invalid transcript result");
            }
        }
        var names=new HashSet<String>();for(Artifact a:result.artifacts())names.add(a.name());
        var playlists=new HashSet<String>();for(var variant:result.variants())playlists.add(variant.playlist());
        var masterChildren=new HashSet<String>();
        for(Artifact a:result.artifacts())if(a.name().endsWith(".m3u8")) {
            String text=Files.readString(stage.resolve(a.name()),StandardCharsets.UTF_8);
            if(!text.startsWith("#EXTM3U")||(!a.name().equals("master.m3u8")&&!text.contains("#EXT-X-ENDLIST")))throw new IOException("Incomplete HLS playlist");
            boolean child=false;
            for(String line:text.split("\\R")) {
                // URI-bearing tags can bypass the normal child-line rewrite and are outside this VOD contract.
                if(line.startsWith("#")&&line.contains("URI="))throw new IOException("Unsupported HLS URI tag");
                if(!line.isBlank()&&!line.startsWith("#")) {
                    if(!line.matches(FileVideos.HLS_CHILD_PATTERN)||!names.contains(line))throw new IOException("Untrusted HLS child");
                    if(a.name().equals("master.m3u8")){if(!playlists.contains(line))throw new IOException("Unknown rendition");masterChildren.add(line);}
                    else if(!line.endsWith(".ts"))throw new IOException("Invalid segment");
                    child=true;
                }
            }
            if(!child)throw new IOException("Empty HLS playlist");
        }
        if(!masterChildren.equals(playlists))throw new IOException("Missing HLS rendition");
    }
    private void schedule(Job job,int seconds){db.update("UPDATE file_media_jobs SET attempts=0,error_code=NULL,next_check_at=now()+(? * interval '1 second'),updated_at=now() WHERE request_id=?",seconds,job.request());}
    private void processed(Job job){db.update("UPDATE file_media_inbox SET processed_at=now() WHERE request_id=? AND processed_at IS NULL",job.request());}
    void defer(Job job,Exception error) {
        if(error instanceof NoedaeriClient.RemoteFailure failure)
            org.slf4j.LoggerFactory.getLogger(getClass()).warn("noedaeri_media_request_failed status={} code={}",failure.status,failure.code);
        else org.slf4j.LoggerFactory.getLogger(getClass()).warn("noedaeri_media_request_failed errorType={}",error.getClass().getSimpleName());
        String code=error instanceof NoedaeriClient.RemoteFailure remote?switch(remote.status) {
            case 401,403->"FILE_MEDIA_REMOTE_AUTH";
            case 410->"FILE_MEDIA_RESULT_EXPIRED";
            case 413->"FILE_MEDIA_INPUT_LIMIT";
            case 503->switch(remote.code){case "platform_delivery_not_configured"->"FILE_MEDIA_NOT_CONFIGURED";case "stt_not_configured","subtitle_renderer_unavailable"->"FILE_VIDEO_SUBTITLES_UNAVAILABLE";default->"FILE_MEDIA_REMOTE_UNAVAILABLE";};
            default->"FILE_MEDIA_REMOTE_UNAVAILABLE";
        }:"FILE_MEDIA_REMOTE_UNAVAILABLE";
        if(job.state().equals("IMPORTED")||db.queryForObject("SELECT state FROM file_media_jobs WHERE request_id=?",String.class,job.request()).equals("IMPORTED")) {
            db.update("UPDATE file_media_jobs SET error_code=?,attempts=attempts+1,next_check_at=now()+interval '5 minutes',updated_at=now() WHERE request_id=?",code,job.request());return;
        }
        if(code.equals("FILE_MEDIA_INPUT_LIMIT")){finishFailure(job,code,"UNSUPPORTED");return;}
        if(Set.of("FILE_MEDIA_RESULT_EXPIRED","FILE_MEDIA_REMOTE_AUTH","FILE_VIDEO_SUBTITLES_UNAVAILABLE").contains(code)||job.attempts()>=7){finishFailure(job,code,"FAILED");return;}
        db.update("UPDATE file_media_jobs SET error_code=?,attempts=attempts+1,next_check_at=now()+(? * interval '1 second'),updated_at=now() WHERE request_id=?",code,Math.min(3600,30L<<job.attempts()),job.request());
        // Do not let an unprocessed notification bypass the retry delay after a download/network failure.
        processed(job);
    }
    private void finishFailure(Job job,String code,String state) {
        tx.executeWithoutResult(s->{
            db.queryForList("SELECT id FROM files WHERE id=? FOR UPDATE",job.file());
            if(current(job)) {
                db.update("UPDATE file_views SET state=?,error_code=?,finished_at=now() WHERE file_id=? AND processing_generation=?",state,code,job.file(),job.generation());
                if(job.video())db.update("UPDATE file_videos SET state=?,error_code=?,finished_at=now() WHERE file_id=? AND generation=?",state,code,job.file(),job.generation());
                db.update("UPDATE files SET video_reserved_bytes=0 WHERE id=?",job.file());
            }
            db.update("UPDATE file_media_jobs SET state='FAILED',error_code=?,updated_at=now() WHERE request_id=?",code,job.request());processed(job);
        });
    }
    static UUID uuid(JsonNode value) throws IOException {try{return UUID.fromString(value.asString());}catch(Exception e){throw new IOException("Invalid remote UUID");}}
    static String extension(String name){int dot=name.lastIndexOf('.');return dot<0?"":name.substring(dot+1).toLowerCase(Locale.ROOT);}
    static void purge(Path directory) throws IOException {if(Files.exists(directory))try(var paths=Files.walk(directory)){for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}}
}
