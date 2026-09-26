package kr.shnea.platform.file;

import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.security.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@Service
class FileViews {
    record View(String state,String kind,String mediaType,boolean thumbnail,String errorCode) {}
    record Links(UUID fileId,String state,String kind,String mediaType,String errorCode,String originalUrl,String previewUrl,
                 String thumbnailUrl,String viewerUrl,String downloadUrl,Instant expiresAt,
                 FileVideos.Status video,String streamUrl,Instant streamExpiresAt) {}
    private final JdbcTemplate db; private final TransactionTemplate tx; private final FilesService files;
    private final FileAccess access; private final FileStore store; private final FileVideos videos;
    FileViews(JdbcTemplate db,TransactionTemplate tx,FilesService files,FileAccess access,FileStore store,FileVideos videos) {
        this.db=db;this.tx=tx;this.files=files;this.access=access;this.store=store;this.videos=videos;
    }
    View view(UUID id) {
        db.update("INSERT INTO file_views(file_id) VALUES (?) ON CONFLICT DO NOTHING",id);
        return db.queryForObject("SELECT * FROM file_views WHERE file_id=?",(r,n)->new View(r.getString("state"),r.getString("kind"),r.getString("media_type"),r.getBoolean("thumbnail"),r.getString("error_code")),id);
    }
    Links manage(UUID id,FileAccess.Context context,Instant expiry) {
        return tx.execute(s->{
            db.execute("SET LOCAL lock_timeout='5s'");db.queryForList("SELECT id FROM files WHERE id=? FOR UPDATE",id);
            var info=files.detail(id,context);String token=null;Instant end=null;
            if(info.visibility().equals("PRIVATE")) {
                end=Instant.now().plusSeconds(300);if(expiry!=null&&expiry.isBefore(end))end=expiry;
                if(!end.isAfter(Instant.now()))throw FileFailure.missing();
                byte[] bytes=new byte[32];new SecureRandom().nextBytes(bytes);token=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
                db.update("DELETE FROM file_view_tokens WHERE file_id=? AND greatest(expires_at,playback_expires_at)<=now()",id);
                // Keep at most 20 short-lived viewer sessions per file; do not invalidate an active video on refresh.
                db.update("DELETE FROM file_view_tokens WHERE token_hash IN (SELECT token_hash FROM file_view_tokens WHERE file_id=? AND share_id IS NULL ORDER BY expires_at DESC OFFSET 19)",id);
                db.update("INSERT INTO file_view_tokens(token_hash,file_id,access_revision,expires_at,playback_expires_at) SELECT ?,id,access_revision,?,? FROM files WHERE id=?",hash(token),java.sql.Timestamp.from(end),
                    FileVideos.candidate(info.originalName())?java.sql.Timestamp.from(Instant.now().plusSeconds(7200)):null,id);
            }
            return links(id,token,end);
        });
    }
    FilesService.Row authorize(UUID id,String token,String key) {
        return authorize(id,token,key,false);
    }
    FilesService.Row authorize(UUID id,String token,String key,boolean playback) {
        if(token!=null&&!token.matches("[A-Za-z0-9_-]{43}"))throw FileFailure.missing();
        var row=files.downloadable(id);
        if(key!=null) {files.sameEnvironment(row,access.require(key,"files:read"));return files.downloadable(id);}
        access.requireActive(row.environment());row=files.downloadable(id);
        if(row.visibility().equals("PRIVATE")||token!=null) {
            if(token==null||db.queryForObject("SELECT count(*) FROM file_view_tokens t JOIN files f ON f.id=t.file_id WHERE t.file_id=? AND t.token_hash=? AND t."+(playback?"playback_expires_at":"expires_at")+">now() AND t.access_revision=f.access_revision AND (t.share_id IS NULL OR EXISTS (SELECT 1 FROM file_shares s WHERE s.id=t.share_id AND s.file_id=t.file_id AND s.revoked_at IS NULL AND s.expires_at>now() AND s.access_revision=f.access_revision))",Long.class,id,hash(token))!=1)throw FileFailure.missing();
        }
        return row;
    }
    Links links(UUID id,String token,Instant expiry) {
        if(token!=null&&expiry==null)expiry=db.query("SELECT expires_at FROM file_view_tokens WHERE token_hash=? AND file_id=?",(r,n)->r.getTimestamp(1).toInstant(),hash(token),id).stream().findFirst().orElse(null);
        View v=view(id);String base="/api/v1/files/"+id;String query=token==null?"":"?token="+token;
        var video=FileVideos.candidate(files.downloadable(id).name())?videos.status(id):null;
        Instant streamEnd=token==null?null:db.query("SELECT playback_expires_at FROM file_view_tokens WHERE token_hash=? AND file_id=? AND playback_expires_at IS NOT NULL",(r,n)->r.getTimestamp(1).toInstant(),hash(token),id).stream().findFirst().orElse(null);
        return new Links(id,v.state(),video==null?v.kind():"VIDEO",v.mediaType(),v.errorCode(),base+"/content/original"+query,
            v.state().equals("READY")?base+"/content/preview"+query:null,v.thumbnail()?base+"/content/thumbnail"+query:null,
            base+"/view"+query,base+"/content/download"+query,expiry,video,video!=null&&video.state().equals("READY")?base+"/hls/master.m3u8"+query:null,streamEnd);
    }
    Object retry(UUID id,FileAccess.Context context) {
        return tx.execute(s->{
            db.queryForList("SELECT id FROM files WHERE id=? FOR UPDATE",id);files.detail(id,context);view(id);
            db.update("UPDATE file_views SET state='QUEUED',attempts=0,error_code=NULL WHERE file_id=? AND state='FAILED'",id);
            return view(id);
        });
    }
    @Scheduled(fixedDelay=3000,initialDelay=15000)
    void work() {
        db.update("DELETE FROM file_view_tokens WHERE greatest(expires_at,playback_expires_at)<=now()");
        // Discover completed files, including files uploaded before this feature was installed.
        db.update("INSERT INTO file_views(file_id) SELECT f.id FROM files f LEFT JOIN file_views v ON v.file_id=f.id WHERE f.state='READY' AND v.file_id IS NULL ORDER BY f.completed_at LIMIT 100 ON CONFLICT DO NOTHING");
        db.update("UPDATE file_views SET state=CASE WHEN attempts<3 THEN 'QUEUED' ELSE 'FAILED' END,error_code='FILE_PREVIEW_INTERRUPTED' WHERE state='PROCESSING' AND started_at<now()-interval '2 minutes'");
        UUID id=tx.execute(s->{
            var ids=db.queryForList("SELECT v.file_id FROM file_views v JOIN files f ON f.id=v.file_id WHERE v.state='QUEUED' AND f.state='READY' ORDER BY f.completed_at LIMIT 1 FOR UPDATE OF v SKIP LOCKED",UUID.class);
            if(ids.isEmpty())return null;UUID next=ids.getFirst();db.update("UPDATE file_views SET state='PROCESSING',attempts=attempts+1,started_at=now(),error_code=NULL WHERE file_id=?",next);return next;
        });
        if(id!=null)synchronized(store.mediaMonitor){process(id);}
    }
    void process(UUID id) {
        Path temporary=null;
        try {
            var row=files.downloadable(id);access.requireActive(row.environment());
            temporary=Files.createTempFile("platform-preview-",".jpg");View result=inspect(row,temporary);Path output=temporary;
            tx.executeWithoutResult(s->{
                db.queryForList("SELECT id FROM files WHERE id=? FOR UPDATE",id);files.downloadable(id);
                try {if(result.thumbnail())Files.move(output,store.thumbnail(id),StandardCopyOption.REPLACE_EXISTING);}
                catch(IOException e){throw FileFailure.unavailable();}
                db.update("UPDATE file_views SET state=?,kind=?,media_type=?,thumbnail=?,error_code=?,finished_at=now() WHERE file_id=?",result.state(),result.kind(),result.mediaType(),result.thumbnail(),result.errorCode(),id);
                db.update("INSERT INTO file_audit(file_id,environment_id,actor,action) VALUES (?,?,'system:preview',?)",id,row.environment(),"file.preview."+result.state().toLowerCase(Locale.ROOT));
            });
        } catch(Exception e) {
            db.update("UPDATE file_views SET state='FAILED',error_code='FILE_PREVIEW_FAILED',finished_at=now() WHERE file_id=?",id);
        } finally {if(temporary!=null)try{Files.deleteIfExists(temporary);}catch(IOException ignored){}}
    }
    View inspect(FilesService.Row row,Path thumbnail) throws Exception {
        String ext=row.name().substring(row.name().lastIndexOf('.')+1).toLowerCase(Locale.ROOT);
        if(Set.of("txt","md").contains(ext)) {
            if(row.size()>262144)return unsupported("FILE_PREVIEW_INPUT_LIMIT");
            text(row.id());return new View("READY",ext.equals("md")?"MARKDOWN":"TEXT","text/plain; charset=UTF-8",false,null);
        }
        byte[] head;try(var in=store.open(row.id())){head=in.readNBytes(16);}
        if(ext.equals("pdf")) {
            if(row.size()>100_000_000)return unsupported("FILE_PREVIEW_INPUT_LIMIT");
            if(!new String(head,StandardCharsets.ISO_8859_1).startsWith("%PDF-"))throw new IOException("Invalid PDF");
            return new View("READY","PDF","application/pdf",false,null);
        }
        if(!Set.of("png","jpg","jpeg","gif","webp","mp4","webm","mp3","wav").contains(ext))return unsupported("FILE_PREVIEW_UNSUPPORTED");
        boolean image=Set.of("png","jpg","jpeg","gif","webp").contains(ext);
        if(row.size()>(image?32_000_000:1_000_000_000))return unsupported("FILE_PREVIEW_INPUT_LIMIT");
        String formats="png_pipe,jpeg_pipe,gif,webp_pipe,mov,matroska,mp3,wav";
        byte[] probe=run(List.of("ffprobe","-v","error","-max_alloc","67108864","-protocol_whitelist","file","-format_whitelist",formats,
            "-show_entries","stream=codec_type,codec_name,width,height:format=format_name","-of","json",store.path(row.id()).toString()),15);
        var metadata=new JsonMapper().readTree(probe);var streams=metadata.path("streams");if(streams.isEmpty())throw new IOException("No media stream");
        String format=metadata.path("format").path("format_name").asString();String mime=null,kind=null;boolean visual=false;
        if(image) {
            var first=streams.get(0);String codec=first.path("codec_name").asString();
            String expected=ext.equals("jpg")||ext.equals("jpeg")?"mjpeg":ext;
            if(!codec.equals(expected)||!format.equals(Map.of("png","png_pipe","mjpeg","jpeg_pipe","gif","gif","webp","webp_pipe").get(expected)))throw new IOException("Format mismatch");
            mime="image/"+(codec.equals("mjpeg")?"jpeg":codec);kind="IMAGE";visual=true;
        } else if(ext.equals("mp4")&&format.contains("mp4")) {mime="video/mp4";kind="VIDEO";}
        else if(ext.equals("webm")&&format.contains("webm")){mime="video/webm";kind="VIDEO";}
        else if(ext.equals("mp3")&&format.equals("mp3")){mime="audio/mpeg";kind="AUDIO";}
        else if(ext.equals("wav")&&format.equals("wav")){mime="audio/wav";kind="AUDIO";}
        else throw new IOException("Format mismatch");
        for(var stream:streams) {
            if(stream.path("codec_type").asString().equals("video")) {
                long w=stream.path("width").asLong(0),h=stream.path("height").asLong(0);
                if(w<=0||h<=0||w*h>(image?40_000_000:8_500_000)||w>10000||h>10000)return unsupported("FILE_PREVIEW_INPUT_LIMIT");
                if(kind.equals("VIDEO")&&!Set.of("h264","vp8","vp9","av1").contains(stream.path("codec_name").asString()))return unsupported("FILE_PREVIEW_CODEC_UNSUPPORTED");
                visual=true;
            }
        }
        if(kind.equals("VIDEO")&&!visual)throw new IOException("No video");
        if(visual) {
            if(store.usableSpace()<4*1024*1024)throw new IOException("Insufficient disk");
            run(List.of("ffmpeg","-v","error","-nostdin","-y","-max_alloc","67108864","-threads","1","-protocol_whitelist","file","-format_whitelist",formats,
                "-i",store.path(row.id()).toString(),"-map","0:v:0","-frames:v","1","-vf","scale=480:320:force_original_aspect_ratio=decrease","-threads","1","-filter_threads","1","-f","image2",thumbnail.toString()),30);
            if(Files.size(thumbnail)==0||Files.size(thumbnail)>2_000_000)throw new IOException("Invalid thumbnail");
        }
        return new View("READY",kind,mime,visual,null);
    }
    String text(UUID id) throws IOException {
        try(var in=store.open(id)) {
            byte[] bytes=in.readNBytes(262145);if(bytes.length>262144)throw new IOException("Text too large");
            String value=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            if(value.indexOf('\0')>=0)throw new IOException("Binary text");return value;
        }
    }
    private static View unsupported(String reason){return new View("UNSUPPORTED","OTHER","application/octet-stream",false,reason);}
    static byte[] run(List<String> command,int seconds) throws Exception {
        Path output=Files.createTempFile("platform-probe-",".out");Process process=null;
        try {
            // Fixed shell program, positional arguments only: filenames never become shell source.
            // One worker, 384 MiB virtual address space, 2 MiB per output file, bounded wall time.
            var argv=new ArrayList<>(List.of("sh","-c","ulimit -v 393216; ulimit -f 4096; exec \"$@\"","preview"));argv.addAll(command);
            process=new ProcessBuilder(argv).redirectOutput(output.toFile()).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            if(!process.waitFor(seconds,TimeUnit.SECONDS)||process.exitValue()!=0)throw new IOException("Media process failed");
            if(Files.size(output)>2_000_000)throw new IOException("Probe output too large");return Files.readAllBytes(output);
        } finally {if(process!=null&&process.isAlive()){process.destroyForcibly();process.waitFor(5,TimeUnit.SECONDS);}Files.deleteIfExists(output);}
    }
    static String hash(String token) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII)));}
        catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
}
