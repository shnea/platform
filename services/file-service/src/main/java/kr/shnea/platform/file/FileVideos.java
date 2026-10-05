package kr.shnea.platform.file;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.Connection;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@Service
class FileVideos {
    static final String HLS_CHILD_PATTERN="q[0-9]{1,4}(?:\\.m3u8|-[0-9]{5}\\.ts)|[0-9]{1,4}p(?:\\.m3u8|-[0-9]{5}\\.ts)";
    record Variant(int quality,int width,int height,int bandwidth,String playlist) {}
    record Status(String state,int progress,Double durationSeconds,List<Variant> variants,String errorCode) {}
    record Source(int width,int height,double duration,boolean audio) {}
    private static final String FORMATS="mov,matroska";
    private final JdbcTemplate db; private final TransactionTemplate tx; private final FileStore store;
    private final FilesService files; private final FileAccess access; private final long quota;
    private final JsonMapper json=new JsonMapper();
    FileVideos(JdbcTemplate db,TransactionTemplate tx,FileStore store,FilesService files,FileAccess access,
               @Value("${platform.files.environment-quota:50000000000}") long quota) {
        this.db=db;this.tx=tx;this.store=store;this.files=files;this.access=access;this.quota=quota;
    }
    static boolean candidate(String name) {return name.toLowerCase(Locale.ROOT).matches(".*\\.(mp4|m4v|mov|mkv|webm)$");}
    Status status(UUID id) {
        var rows=db.query("SELECT * FROM file_videos WHERE file_id=?",(r,n)->new Status(r.getString("state"),r.getInt("progress"),
            (Double)r.getObject("duration_seconds"),Arrays.asList(json.readValue(r.getString("variants"),Variant[].class)),r.getString("error_code")),id);
        return rows.isEmpty()?new Status("QUEUED",0,null,List.of(),null):rows.getFirst();
    }
    Status retry(UUID id,FileAccess.Context context) {
        return tx.execute(s->{
            db.queryForList("SELECT id FROM files WHERE id=? FOR UPDATE",id);var info=files.detail(id,context);
            if(!candidate(info.originalName()))throw FileFailure.invalid();
            db.update("INSERT INTO file_videos(file_id) VALUES (?) ON CONFLICT DO NOTHING",id);
            if(db.update("UPDATE file_videos SET state='QUEUED',attempts=0,error_code=NULL,progress=0 WHERE file_id=? AND state='FAILED'",id)>0)
                db.update("INSERT INTO file_audit(file_id,environment_id,actor,action) VALUES (?,?,?,'file.video.retry')",id,context.environmentId(),context.actor());
            return status(id);
        });
    }
    @Scheduled(fixedDelay=5000,initialDelay=20000)
    void work() {
        // ponytail: one conversion for this shared volume. A session lock also excludes a second service replica.
        synchronized(store.mediaMonitor) {
            try(Connection lock=Objects.requireNonNull(db.getDataSource()).getConnection()) {
                boolean held=false;
                try(var statement=lock.createStatement()) {
                    try(var result=statement.executeQuery("SELECT pg_try_advisory_lock(736452919)")){result.next();held=result.getBoolean(1);}
                    if(!held)return;
                    recover();
                    db.update("""
                        INSERT INTO file_videos(file_id) SELECT f.id FROM files f LEFT JOIN file_videos v ON v.file_id=f.id
                        WHERE f.state='READY' AND v.file_id IS NULL AND lower(f.original_name) ~ '\\.(mp4|m4v|mov|mkv|webm)$'
                        ORDER BY f.completed_at LIMIT 100 ON CONFLICT DO NOTHING
                        """);
                    UUID id=tx.execute(s->{
                        var ids=db.queryForList("""
                            SELECT v.file_id FROM file_videos v JOIN files f ON f.id=v.file_id
                            LEFT JOIN file_views p ON p.file_id=f.id
                            WHERE v.state='QUEUED' AND f.state='READY' AND p.state IN ('READY','FAILED','UNSUPPORTED')
                            ORDER BY f.completed_at LIMIT 1 FOR UPDATE OF v SKIP LOCKED
                            """,UUID.class);
                        if(ids.isEmpty())return null;UUID next=ids.getFirst();
                        db.update("UPDATE file_videos SET state='PROCESSING',generation=?,attempts=attempts+1,started_at=now(),heartbeat_at=now(),progress=0,error_code=NULL WHERE file_id=?",UUID.randomUUID(),next);
                        return next;
                    });
                    if(id!=null)process(id,lock);
                }finally{if(held)try(var statement=lock.createStatement()){statement.execute("SELECT pg_advisory_unlock(736452919)");}}
            }catch(Exception e){org.slf4j.LoggerFactory.getLogger(FileVideos.class).warn("video_worker_unavailable");}
        }
    }
    void recover() {
        var stale=db.queryForList("SELECT file_id FROM file_videos WHERE state='PROCESSING' AND heartbeat_at<now()-interval '2 minutes'",UUID.class);
        for(UUID id:stale) {
            store.deleteVideo(id);
            tx.executeWithoutResult(s->{
                db.update("UPDATE files SET video_reserved_bytes=0,video_bytes=0 WHERE id=?",id);
                db.update("UPDATE file_videos SET state=CASE WHEN attempts<3 THEN 'QUEUED' ELSE 'FAILED' END,error_code='FILE_VIDEO_INTERRUPTED',progress=0,finished_at=now() WHERE file_id=?",id);
            });
        }
    }
    void process(UUID id,Connection lock) {
        UUID generation=db.queryForObject("SELECT generation FROM file_videos WHERE file_id=?",UUID.class,id);
        Path output=store.video(id).resolve(generation.toString());boolean ready=false;
        try {
            var row=files.downloadable(id);access.requireActive(row.environment());
            Source source=probe(row);List<Variant> variants=variants(source);
            long reservation=(long)Math.ceil(source.duration()*variants.stream().mapToLong(Variant::bandwidth).sum()/8*1.25)+16*1024*1024;
            reserve(row,reservation);
            store.deleteVideo(id);Files.createDirectories(output);
            int index=0;
            for(Variant variant:variants) {
                int bitrate=videoBitrate(variant.quality());
                var command=new ArrayList<>(List.of("ffmpeg","-v","error","-nostdin","-y","-max_alloc","67108864","-threads","1",
                    "-protocol_whitelist","file","-format_whitelist",FORMATS,"-i",store.path(id).toString(),"-map","0:v:0"));
                if(source.audio())command.addAll(List.of("-map","0:a:0","-c:a","aac","-b:a","128k","-ac","2","-ar","48000"));
                else command.add("-an");
                command.addAll(List.of("-sn","-dn","-map_metadata","-1","-vf","scale="+variant.width()+":"+variant.height()+",setsar=1,fps=30",
                    "-c:v","libx264","-preset","veryfast","-profile:v","high","-pix_fmt","yuv420p","-threads","1","-filter_threads","1",
                    "-b:v",String.valueOf(bitrate),"-maxrate",String.valueOf(bitrate*12/10),"-bufsize",String.valueOf(bitrate*2),
                    "-g","180","-keyint_min","180","-sc_threshold","0","-force_key_frames","expr:gte(t,n_forced*6)",
                    "-t",String.valueOf(source.duration()),"-f","hls","-hls_time","6","-hls_playlist_type","vod","-hls_list_size","0",
                    "-hls_flags","independent_segments","-hls_segment_filename",output.resolve("q"+variant.quality()+"-%05d.ts").toString(),
                    output.resolve(variant.playlist()).toString()));
                convert(id,generation,row.environment(),lock,command,output,reservation,source.duration(),index++,variants.size());
                String playlist=Files.readString(output.resolve(variant.playlist()));
                if(!playlist.contains("#EXT-X-ENDLIST")||!playlist.contains("#EXTINF:"))throw new IOException("Incomplete video");
            }
            StringBuilder master=new StringBuilder("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-INDEPENDENT-SEGMENTS\n");
            for(Variant v:variants)master.append("#EXT-X-STREAM-INF:BANDWIDTH=").append(v.bandwidth()).append(",RESOLUTION=").append(v.width()).append('x').append(v.height()).append(",FRAME-RATE=30.000\n").append(v.playlist()).append('\n');
            Files.writeString(output.resolve("master.m3u8"),master);long bytes=size(output);
            if(bytes>reservation)throw new FileFailure("FILE_VIDEO_OUTPUT_LIMIT",409,"영상 변환 결과가 예약 용량을 초과했습니다.");
            Path thumbnail=output.resolve("thumbnail.jpg");
            FileViews.run(List.of("ffmpeg","-v","error","-nostdin","-y","-max_alloc","67108864","-threads","1","-protocol_whitelist","file","-format_whitelist","mpegts",
                "-i",output.resolve("q"+variants.getFirst().quality()+"-00000.ts").toString(),"-map","0:v:0","-frames:v","1","-vf","scale=480:320:force_original_aspect_ratio=decrease",
                "-threads","1","-filter_threads","1","-f","image2",thumbnail.toString()),30);
            access.requireActive(row.environment());
            tx.executeWithoutResult(s->{
                db.queryForList("SELECT id FROM files WHERE id=? FOR UPDATE",id);files.downloadable(id);
                try{Files.move(thumbnail,store.thumbnail(id),StandardCopyOption.REPLACE_EXISTING);}catch(IOException e){throw FileFailure.unavailable();}
                db.update("UPDATE file_views SET thumbnail=true WHERE file_id=?",id);
                if(db.update("UPDATE file_videos SET state='READY',progress=100,duration_seconds=?,variants=?::jsonb,finished_at=now() WHERE file_id=? AND generation=? AND state='PROCESSING'",source.duration(),json.writeValueAsString(variants),id,generation)!=1)throw FileFailure.missing();
                db.update("UPDATE files SET video_bytes=?,video_reserved_bytes=0 WHERE id=?",bytes,id);
                db.update("INSERT INTO file_audit(file_id,environment_id,actor,action) VALUES (?,?,'system:video','file.video.ready')",id,row.environment());
            });ready=true;
        }catch(Exception e) {
            String code=e instanceof FileFailure failure?failure.code:"FILE_VIDEO_FAILED";
            String state=Set.of("FILE_VIDEO_INPUT_LIMIT","FILE_VIDEO_UNSUPPORTED").contains(code)?"UNSUPPORTED":"FAILED";
            try {
                store.deleteVideo(id);
                tx.executeWithoutResult(s->{
                    db.update("UPDATE files SET video_reserved_bytes=0,video_bytes=0 WHERE id=?",id);
                    db.update("UPDATE file_videos SET state=?,error_code=?,finished_at=now() WHERE file_id=? AND generation=?",state,code,id,generation);
                    db.update("INSERT INTO file_audit(file_id,environment_id,actor,action) SELECT id,environment_id,'system:video',? FROM files WHERE id=?","file.video."+state.toLowerCase(Locale.ROOT),id);
                });
            }catch(RuntimeException ignored){/* Keep PROCESSING/reservation for durable recovery. */}
        }finally{if(!ready)org.slf4j.LoggerFactory.getLogger(FileVideos.class).info("video_conversion_incomplete fileId={}",id);}
    }
    void reserve(FilesService.Row row,long bytes) {
        tx.executeWithoutResult(s->{
            db.execute("SET LOCAL lock_timeout='5s'");db.execute("SELECT pg_advisory_xact_lock(736452918)");
            db.queryForList("SELECT id FROM files WHERE id=? FOR UPDATE",row.id());files.downloadable(row.id());
            long used=db.queryForObject("SELECT coalesce(sum(size_bytes+video_bytes+video_reserved_bytes),0) FROM files WHERE environment_id=? AND purged_at IS NULL",Long.class,row.environment());
            if(bytes>quota-used)throw new FileFailure("FILE_QUOTA_EXCEEDED",409,"환경의 변환 파일 저장 한도가 부족합니다.");
            long reserved=db.queryForObject("SELECT coalesce(sum(CASE WHEN state='UPLOADING' THEN size_bytes-received_bytes ELSE 0 END+video_reserved_bytes),0) FROM files WHERE purged_at IS NULL",Long.class);
            if(bytes>store.usableSpace()-reserved)throw new FileFailure("FILE_STORAGE_FULL",507,"영상 변환에 필요한 저장 공간이 부족합니다.");
            db.update("UPDATE files SET video_reserved_bytes=? WHERE id=?",bytes,row.id());
        });
    }
    Source probe(FilesService.Row row) throws Exception {
        byte[] result=FileViews.run(List.of("ffprobe","-v","error","-max_alloc","67108864","-protocol_whitelist","file","-format_whitelist",FORMATS,
            "-show_entries","stream=codec_type,codec_name,width,height,sample_aspect_ratio:stream_side_data=rotation:format=duration,format_name","-of","json",store.path(row.id()).toString()),15);
        var data=json.readTree(result);double duration=data.path("format").path("duration").asDouble(0);
        int w=0,h=0;boolean audio=false;
        for(var stream:data.path("streams")) {
            if(stream.path("codec_type").asString().equals("audio"))audio=true;
            if(w==0&&stream.path("codec_type").asString().equals("video")) {
                if(!Set.of("h264","hevc","vp8","vp9","av1","mpeg4","mjpeg").contains(stream.path("codec_name").asString()))throw unsupported();
                String sar=stream.path("sample_aspect_ratio").asString("");if(!Set.of("","N/A","1:1").contains(sar))throw unsupported();
                w=stream.path("width").asInt();h=stream.path("height").asInt();
                for(var side:stream.path("side_data_list"))if(Math.abs(side.path("rotation").asInt())%180==90){int swap=w;w=h;h=swap;break;}
            }
        }
        if(!Double.isFinite(duration)||duration<=0||duration>3600||w<2||h<2||w>4096||h>4096||(long)w*h>8_500_000)
            throw new FileFailure("FILE_VIDEO_INPUT_LIMIT",422,"영상 변환은 60분 이하, 최대 4096px·850만 화소 입력을 지원합니다.");
        return new Source(w,h,duration,audio);
    }
    static List<Variant> variants(Source s) {
        int shorter=Math.min(s.width(),s.height());var levels=new ArrayList<Integer>();
        for(int q:List.of(480,720,1080))if(q<=shorter)levels.add(q);
        if(levels.isEmpty())levels.add(shorter/2*2);
        return levels.stream().map(q->{double scale=(double)q/shorter;int w=Math.max(2,(int)(s.width()*scale)/2*2),h=Math.max(2,(int)(s.height()*scale)/2*2);
            return new Variant(q,w,h,(videoBitrate(q)*12/10+(s.audio()?128000:0))*12/10,"q"+q+".m3u8");}).toList();
    }
    private static int videoBitrate(int quality){return quality<=360?800000:quality<=480?1200000:quality<=720?2500000:4500000;}
    private static FileFailure unsupported(){return new FileFailure("FILE_VIDEO_UNSUPPORTED",422,"지원하지 않는 영상 코덱 또는 픽셀 비율입니다.");}
    private void convert(UUID id,UUID generation,UUID environment,Connection lock,List<String> command,Path output,long limit,double duration,int index,int total) throws Exception {
        var argv=new ArrayList<>(List.of("sh","-c","ulimit -v 524288; ulimit -f 32768; exec \"$@\"","video"));argv.addAll(command);
        Process process=new ProcessBuilder(argv).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos((long)Math.min(14400,Math.max(120,duration*8)));
        try {
            while(!process.waitFor(2,TimeUnit.SECONDS)) {
                if(System.nanoTime()>deadline)throw new FileFailure("FILE_VIDEO_TIMEOUT",409,"영상 변환 제한 시간을 초과했습니다.");
                if(!lock.isValid(2))throw new IOException("Worker lock lost");
                files.downloadable(id);access.requireActive(environment);
                if(store.usableSpace()<8*1024*1024||size(output)>limit)throw new FileFailure("FILE_VIDEO_OUTPUT_LIMIT",409,"영상 변환 공간 한도를 초과했습니다.");
                // Completed renditions are real progress; do not invent a time-based percentage.
                if(db.update("UPDATE file_videos SET heartbeat_at=now(),progress=? WHERE file_id=? AND generation=? AND state='PROCESSING'",index*100/total,id,generation)!=1)throw new IOException("Job replaced");
            }
            if(process.exitValue()!=0)throw new IOException("Video encoder failed");
        }finally{if(process.isAlive()){process.destroyForcibly();process.waitFor(5,TimeUnit.SECONDS);}}
    }
    static long size(Path directory) throws IOException {
        if(!Files.exists(directory))return 0;
        try(var paths=Files.walk(directory)){long sum=0;for(Path p:paths.filter(Files::isRegularFile).toList())sum+=Files.size(p);return sum;}
    }
    Path asset(UUID id,String name) {
        if(!name.matches("master\\.m3u8|"+HLS_CHILD_PATTERN))throw FileFailure.missing();
        UUID generation=db.query("SELECT generation FROM file_videos WHERE file_id=? AND state='READY'",(r,n)->r.getObject(1,UUID.class),id).stream().findFirst().orElseThrow(FileFailure::missing);
        Path path=store.video(id).resolve(generation.toString()).resolve(name);
        if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS))throw FileFailure.missing();return path;
    }
    String playlist(UUID id,String name,String token) throws IOException {
        Path path=asset(id,name);if(Files.size(path)>262144)throw FileFailure.unavailable();
        StringBuilder result=new StringBuilder();
        for(String line:Files.readAllLines(path,StandardCharsets.UTF_8)) {
            if(!line.isBlank()&&!line.startsWith("#")) {
                if(!line.matches(HLS_CHILD_PATTERN))throw FileFailure.unavailable();
                line="/api/v1/files/"+id+"/hls/"+line+(token==null?"":"?token="+token);
            }
            result.append(line).append('\n');
        }
        return result.toString();
    }
}
