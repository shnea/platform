package kr.shnea.platform.file;

import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="JOB_TEST_DB_URL", matches="jdbc:postgresql://[^/]+/job_checks")
@Timeout(30)
class FilesDatabaseTest {
    @TempDir Path directory;
    JdbcTemplate admin, db;
    TransactionTemplate tx;
    FileStore store;
    FilesService service;
    String schema;
    final FileAccess.Context owner = new FileAccess.Context(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
    final byte[] bytes = "파일 업로드 검증\n<script>alert(1)</script>".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    @BeforeEach void setup() throws Exception {
        String url = System.getenv("JOB_TEST_DB_URL");
        schema = "file_test_" + UUID.randomUUID().toString().replace("-", "");
        admin = new JdbcTemplate(new DriverManagerDataSource(url, "job_checks", "isolated-test-only"));
        admin.execute("CREATE SCHEMA " + schema);
        var ds = new DriverManagerDataSource(url + "?currentSchema=" + schema, "job_checks", "isolated-test-only");
        Flyway.configure().dataSource(ds).defaultSchema(schema).locations("classpath:db/migration").load().migrate();
        db = new JdbcTemplate(ds);
        tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        store = new FileStore(directory.toString(), db);
        service = new FilesService(db, tx, store, 10_000_000, 20);
    }
    @AfterEach void cleanup() { if (admin != null) admin.execute("DROP SCHEMA " + schema + " CASCADE"); }
    RetentionService retention() {return new RetentionService(db,tx,org.mockito.Mockito.mock(FileAccess.class));}
    FileVideos videos(){return new FileVideos(db,tx,store,service,org.mockito.Mockito.mock(FileAccess.class),10_000_000,new MediaBackend("local"));}
    FileViews views(){return new FileViews(db,tx,service,org.mockito.Mockito.mock(FileAccess.class),store,videos(),new MediaBackend("local"));}
    class RemoteMediaFixture implements AutoCloseable {
        final UUID id,remote=UUID.randomUUID(),event=UUID.randomUUID();
        UUID request;
        final boolean video;
        boolean uploaded,succeeded,loseUploadResponse;
        int creates,uploads,receipts,cancels;
        final com.sun.net.httpserver.HttpServer server;
        final NoedaeriClient client;final NoedaeriMedia media;
        final tools.jackson.databind.json.JsonMapper json=new tools.jackson.databind.json.JsonMapper();
        final Map<String,byte[]> artifacts=new LinkedHashMap<>();
        Map<String,Object> subtitleManifest;
        tools.jackson.databind.JsonNode submittedOptions;
        RemoteMediaFixture(boolean video) throws Exception {
            this.video=video;id=videoFile();db.update("UPDATE files SET original_name=? WHERE id=?",video?"video.mp4":"photo.png",id);
            artifacts.put("thumbnail.jpg",new byte[]{(byte)0xff,(byte)0xd8,(byte)0xff,0});
            if(video) {
                artifacts.put("master.m3u8","#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1200000\n480p.m3u8\n".getBytes());
                artifacts.put("480p.m3u8","#EXTM3U\n#EXTINF:6,\n480p-00000.ts\n#EXT-X-ENDLIST\n".getBytes());
                artifacts.put("480p-00000.ts","segment".getBytes());
            }else artifacts.put("preview.webp","RIFF0000WEBP".getBytes());
            server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
            server.createContext("/api/v1/jobs",exchange->{
                try {
                    assertThat(exchange.getRequestHeaders().getFirst("X-Noedaeri-API-Key")).isEqualTo("test-request-key");
                    String path=exchange.getRequestURI().getPath();byte[] response;int status=200;
                    if(path.equals("/api/v1/jobs")) {
                        var body=json.readTree(exchange.getRequestBody().readAllBytes());request=UUID.fromString(body.path("idempotency_key").asString());submittedOptions=body.path("options");creates++;
                        assertThat(body.path("kind").asString()).isEqualTo(video?"video.package":"image.package");
                        assertThat(body.path("input").path("type").asString()).isEqualTo("upload");
                        if(!video)assertThat(body.path("input").path("extension").asString()).isEqualTo("png");
                        response=json.writeValueAsBytes(status());
                    }else if(path.endsWith("/input")) {
                        assertThat(exchange.getRequestBody().readAllBytes()).isEqualTo(bytes);uploads++;uploaded=true;
                        status=loseUploadResponse?503:200;response="{}".getBytes();
                    }else if(path.endsWith("/receipt")) {
                        assertThat(db.queryForObject("SELECT state FROM file_media_jobs WHERE request_id=?",String.class,request)).isEqualTo("IMPORTED");
                        assertThat(Files.readAllBytes(store.thumbnail(id))).isEqualTo(artifacts.get("thumbnail.jpg"));
                        if(subtitleManifest!=null)for(String name:VideoOptions.ARTIFACTS)
                            assertThat(Files.readAllBytes(store.mediaOutput(id,job().generation(),true).resolve(name))).isEqualTo(artifacts.get(name));
                        receipts++;response="{\"accepted\":true,\"cleanup\":\"scheduled\"}".getBytes();
                    }else if(path.endsWith("/cancel")){cancels++;response="{}".getBytes();}
                    else if(path.contains("/files/"))response=artifacts.get(path.substring(path.lastIndexOf('/')+1));
                    else response=json.writeValueAsBytes(status());
                    exchange.sendResponseHeaders(status,response.length);exchange.getResponseBody().write(response);
                }finally{exchange.close();}
            });server.start();
            client=new NoedaeriClient(java.net.URI.create("http://127.0.0.1:"+server.getAddress().getPort()),"test-request-key","shared-test-secret",536870912);
            media=new NoedaeriMedia(db,tx,store,service,videos(),org.mockito.Mockito.mock(FileAccess.class),client,new MediaBackend("noedaeri"));
        }
        Map<String,Object> result() {
            if(video) {
                var sizes=new LinkedHashMap<String,Long>();artifacts.forEach((name,body)->sizes.put(name,(long)body.length));
                var result=new LinkedHashMap<String,Object>(Map.of("type","video_package","master","master.m3u8","thumbnail","thumbnail.jpg","duration_seconds",12,
                    "variants",List.of(Map.of("label","480p","width",852,"height",480,"bandwidth",1200000,"playlist","480p.m3u8")),
                    "files",new ArrayList<>(artifacts.keySet()),"file_sizes",sizes));
                if(subtitleManifest!=null)result.put("subtitles",subtitleManifest);
                return result;
            }
            return Map.of("type","image_package","source",Map.of("media_type","image/png","width",100,"height",100),
                "thumbnail",Map.of("name","thumbnail.jpg","width",100,"height",100,"media_type","image/jpeg","bytes",artifacts.get("thumbnail.jpg").length),
                "preview",Map.of("name","preview.webp","width",100,"height",100,"media_type","image/webp","bytes",artifacts.get("preview.webp").length),"files",new ArrayList<>(artifacts.keySet()));
        }
        Map<String,Object> status(){return Map.of("id",remote.toString(),"kind",video?"video.package":"image.package","idempotency_key",request.toString(),
            "status",succeeded?"succeeded":uploaded?"queued":"uploading","terminal_event_id",event.toString(),"expires_at",java.time.Instant.now().plusSeconds(3600).toString(),"result",result());}
        NoedaeriMedia.Job job() {
            return db.queryForObject("SELECT * FROM file_media_jobs WHERE file_id=?",(r,n)->new NoedaeriMedia.Job(r.getObject("request_id",UUID.class),id,r.getString("kind"),
                r.getObject("generation",UUID.class),r.getObject("job_id",UUID.class),r.getString("state"),r.getObject("event_id",UUID.class),r.getInt("attempts")),id);
        }
        byte[] eventBody() {return json.writeValueAsBytes(Map.of("version",1,"event_id",event.toString(),"job_id",remote.toString(),"idempotency_key",request.toString(),
            "type","job.succeeded","job",Map.of("kind",video?"video.package":"image.package","status","succeeded","result",result())));}
        String signature(byte[] body,String timestamp) throws Exception {
            var mac=javax.crypto.Mac.getInstance("HmacSHA256");mac.init(new javax.crypto.spec.SecretKeySpec(client.webhookSecret.getBytes(java.nio.charset.StandardCharsets.UTF_8),"HmacSHA256"));
            mac.update((timestamp+".").getBytes(java.nio.charset.StandardCharsets.US_ASCII));return "sha256="+HexFormat.of().formatHex(mac.doFinal(body));
        }
        void due(){db.update("UPDATE file_media_jobs SET next_check_at=now() WHERE file_id=?",id);}
        @Override public void close(){server.stop(0);}
    }
    @Test void remoteImageWebhookIsDurableDeduplicatedAndReceiptFollowsStorage() throws Exception {
        try(var fixture=new RemoteMediaFixture(false)) {
            fixture.media.work();assertThat(fixture.uploads).isEqualTo(1);assertThat(fixture.job().state()).isEqualTo("ACTIVE");
            fixture.succeeded=true;
            var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(new NoedaeriWebhook(db,tx,fixture.client)).setControllerAdvice(new FileErrors()).build();
            byte[] body=fixture.eventBody();String timestamp=Long.toString(java.time.Instant.now().getEpochSecond()),signature=fixture.signature(body,timestamp);
            for(String endpoint:List.of("/api/webhooks/noedaeri","/api/v1/files/integrations/noedaeri/events"))mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(endpoint)
                .contentType("application/json").content(body).header("X-Noedaeri-Event-ID",fixture.event.toString()).header("X-Noedaeri-Timestamp",timestamp).header("X-Noedaeri-Signature",signature))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
            assertThat(db.queryForObject("SELECT count(*) FROM file_media_inbox",Integer.class)).isEqualTo(1);
            byte[] malformed=new String(body,java.nio.charset.StandardCharsets.UTF_8).replace(fixture.event.toString(),"bad-event").getBytes(java.nio.charset.StandardCharsets.UTF_8);
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/webhooks/noedaeri")
                .contentType("application/json").content(malformed).header("X-Noedaeri-Event-ID","bad-event").header("X-Noedaeri-Timestamp",timestamp).header("X-Noedaeri-Signature",fixture.signature(malformed,timestamp)))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/webhooks/noedaeri")
                .contentType("application/json").content(malformed).header("X-Noedaeri-Event-ID","bad-event").header("X-Noedaeri-Timestamp",timestamp).header("X-Noedaeri-Signature",signature))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
            assertThat(fixture.receipts).isZero();fixture.media.work();assertThat(fixture.job().state()).isEqualTo("IMPORTED");
            assertThat(fixture.receipts).isZero();assertThat(views().view(fixture.id).kind()).isEqualTo("IMAGE");
            assertThat(Files.readAllBytes(store.preview(fixture.id))).isEqualTo(fixture.artifacts.get("preview.webp"));
            assertThat(Files.readAllBytes(store.path(fixture.id))).isEqualTo(bytes);
            fixture.media.work();assertThat(fixture.receipts).isEqualTo(1);
            service.visibility(fixture.id,owner,"PRIVATE");code("FILE_NOT_FOUND",()->views().authorize(fixture.id,null,null));
            var links=views().manage(fixture.id,owner,null);String token=links.viewerUrl().split("token=")[1];
            assertThat(views().authorize(fixture.id,token,null).id()).isEqualTo(fixture.id);
        }
    }
    @Test void remoteVideoUsesStatusRecoveryAndNeverRunsLegacyStaleRecovery() throws Exception {
        try(var fixture=new RemoteMediaFixture(true)) {
            fixture.media.work();assertThat(fixture.uploads).isEqualTo(1);
            db.update("UPDATE file_videos SET heartbeat_at=now()-interval '1 hour' WHERE file_id=?",fixture.id);
            db.update("UPDATE file_views SET started_at=now()-interval '1 hour' WHERE file_id=?",fixture.id);
            videos().recover();assertThat(videos().status(fixture.id).state()).isEqualTo("PROCESSING");
            var remoteViews=new FileViews(db,tx,service,org.mockito.Mockito.mock(FileAccess.class),store,videos(),new MediaBackend("noedaeri"));
            remoteViews.work();assertThat(views().view(fixture.id).state()).isEqualTo("PROCESSING");
            fixture.succeeded=true;fixture.due();fixture.media.work();
            assertThat(videos().status(fixture.id).state()).isEqualTo("READY");
            assertThat(videos().status(fixture.id).variants()).extracting(FileVideos.Variant::quality).containsExactly(480);
            assertThat(videos().playlist(fixture.id,"master.m3u8","capability")).contains("480p.m3u8?token=capability");
            assertThat(videos().playlist(fixture.id,"480p.m3u8","capability")).contains("480p-00000.ts?token=capability");
            assertThat(Files.readAllBytes(store.thumbnail(fixture.id))).isEqualTo(fixture.artifacts.get("thumbnail.jpg"));
            fixture.media.work();assertThat(fixture.receipts).isEqualTo(1);
        }
    }
    @Test void lostUploadResponseReusesRemoteJobAndDoesNotReuploadQueuedInput() throws Exception {
        try(var fixture=new RemoteMediaFixture(false)) {
            fixture.loseUploadResponse=true;fixture.media.work();assertThat(fixture.uploads).isEqualTo(1);
            fixture.due();fixture.media.work();assertThat(fixture.creates).isEqualTo(1);assertThat(fixture.uploads).isEqualTo(1);
            fixture.succeeded=true;fixture.due();fixture.media.work();assertThat(fixture.job().state()).isEqualTo("IMPORTED");
        }
    }
    void captions(RemoteMediaFixture fixture,String mode,int cueCount) {
        var options=VideoOptions.normalize(new VideoOptions(0.0,new VideoOptions.Subtitles(mode,"ko",true)));
        db.update("UPDATE files SET video_options=?::jsonb WHERE id=?",VideoOptions.encode(options),fixture.id);
        fixture.subtitleManifest=Map.of("mode",mode,"language","ko","timing","vad_proportional","cue_count",cueCount,"srt","subtitles.srt","vtt","subtitles.vtt","transcript","transcript.json");
        fixture.artifacts.put("subtitles.srt",(cueCount==0?"":"1\n00:00:00,000 --> 00:00:01,000\n자동 자막\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        fixture.artifacts.put("subtitles.vtt",(cueCount==0?"WEBVTT\n\n":"WEBVTT\n\n00:00:00.000 --> 00:00:01.000\n자동 자막\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        fixture.artifacts.put("transcript.json","{\"text\":\"자동 자막\",\"segments\":[]}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        fixture.artifacts.put("transcript.txt",(cueCount==0?"":"자동 자막").getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    @Test void subtitlesUseOneJobAndUploadAndAllArtifactsAreStoredBeforeReceipt() throws Exception {
        for(String mode:List.of("sidecar","burned"))try(var fixture=new RemoteMediaFixture(true)) {
            captions(fixture,mode,1);fixture.media.work();
            assertThat(fixture.submittedOptions.path("subtitles").path("mode").asString()).isEqualTo(mode);
            assertThat(fixture.submittedOptions.path("subtitles").path("language").asString()).isEqualTo("ko");
            assertThat(fixture.submittedOptions.path("subtitles").path("use_itn").asBoolean()).isTrue();
            fixture.succeeded=true;fixture.due();fixture.media.work();
            assertThat(videos().status(fixture.id).subtitles().mode()).isEqualTo(mode);
            assertThat(fixture.receipts).isZero();fixture.media.work();assertThat(fixture.receipts).isEqualTo(1);
            assertThat(fixture.creates).isEqualTo(1);assertThat(fixture.uploads).isEqualTo(1);
            service.visibility(fixture.id,owner,"PRIVATE");code("FILE_NOT_FOUND",()->views().authorize(fixture.id,null,null,true));
            var links=views().manage(fixture.id,owner,null);assertThat(links.subtitleUrls()).hasSize(4);
            String token=links.streamUrl().split("token=")[1];
            var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(new FileVideosController(videos(),views(),service,org.mockito.Mockito.mock(FileAccess.class))).setControllerAdvice(new FileErrors()).build();
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/files/"+fixture.id+"/hls/subtitles.vtt").param("token",token))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().contentType("text/vtt; charset=UTF-8"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().bytes(fixture.artifacts.get("subtitles.vtt")));
            service.visibility(fixture.id,owner,"PUBLIC");code("FILE_NOT_FOUND",()->views().authorize(fixture.id,token,null,true));
        }
    }
    @Test void silentSubtitlesCanPersistEmptySrtAndTranscript() throws Exception {
        try(var fixture=new RemoteMediaFixture(true)) {
            captions(fixture,"sidecar",0);fixture.media.work();fixture.succeeded=true;fixture.due();fixture.media.work();
            assertThat(videos().status(fixture.id).subtitles().cueCount()).isZero();
            assertThat(Files.size(videos().asset(fixture.id,"subtitles.srt"))).isZero();fixture.media.work();assertThat(fixture.receipts).isEqualTo(1);
        }
    }
    @Test void requestedCaptionsCannotSilentlyFallBackOrExposePartialResults() throws Exception {
        for(String invalid:List.of("missing","mode","path","size","vtt","deleted"))try(var fixture=new RemoteMediaFixture(true)) {
            captions(fixture,"sidecar",1);fixture.media.work();
            if(invalid.equals("missing"))fixture.subtitleManifest=null;
            if(invalid.equals("mode")){var changed=new LinkedHashMap<>(fixture.subtitleManifest);changed.put("mode","burned");fixture.subtitleManifest=changed;}
            if(invalid.equals("path")){var changed=new LinkedHashMap<>(fixture.subtitleManifest);changed.put("vtt","../subtitles.vtt");fixture.subtitleManifest=changed;}
            if(invalid.equals("size"))fixture.artifacts.put("subtitles.vtt",new byte[16*1024*1024+1]);
            if(invalid.equals("vtt"))fixture.artifacts.put("subtitles.vtt","<html>invalid</html>".getBytes());
            if(invalid.equals("deleted"))service.delete(fixture.id,owner);
            fixture.succeeded=true;fixture.due();fixture.media.work();
            assertThat(fixture.receipts).isZero();assertThat(videos().status(fixture.id).state()).isNotEqualTo("READY");
            assertThat(Files.exists(store.mediaOutput(fixture.id,fixture.job().generation(),true))).isFalse();
        }
    }
    @Test void uploadOptionsAreNormalizedIdempotentScopedAndRestoredOnResume() throws Exception {
        UUID request=UUID.randomUUID();var options=new VideoOptions(null,new VideoOptions.Subtitles("sidecar",null,null));
        var input=new FilesService.Create(request,"video.mp4",(long)bytes.length,hash(bytes),"PRIVATE","default",options);
        UUID id=service.create(owner,input).uploadId();assertThat(service.create(owner,input).uploadId()).isEqualTo(id);
        assertThat(service.resumable(owner).getFirst().videoOptions().subtitles()).isEqualTo(new VideoOptions.Subtitles("sidecar","auto",true));
        code("FILE_REQUEST_CONFLICT",()->service.create(owner,new FilesService.Create(request,"video.mp4",(long)bytes.length,hash(bytes),"PRIVATE","default",new VideoOptions(0.0,new VideoOptions.Subtitles("burned","auto",true)))));
        code("INVALID_REQUEST",()->service.create(owner,new FilesService.Create(UUID.randomUUID(),"photo.png",0L,hash(new byte[0]),null,null,options)));
        code("INVALID_REQUEST",()->VideoOptions.normalize(new VideoOptions(0.0,new VideoOptions.Subtitles("invalid","ko",true))));
        code("INVALID_REQUEST",()->VideoOptions.normalize(new VideoOptions(Double.NaN,null)));
        org.springframework.test.util.ReflectionTestUtils.setField(service,"processingBackend","local");
        code("FILE_VIDEO_SUBTITLES_UNAVAILABLE",()->service.create(owner,new FilesService.Create(UUID.randomUUID(),"video.mp4",0L,hash(new byte[0]),null,null,options)));
    }
    @Test void deletedFileAndReplacedGenerationRejectLateRemoteResults() throws Exception {
        for(boolean deleted:List.of(true,false))try(var fixture=new RemoteMediaFixture(true)) {
            fixture.media.work();UUID replacement=UUID.randomUUID();
            if(deleted)service.delete(fixture.id,owner);else db.update("UPDATE file_videos SET generation=? WHERE file_id=?",replacement,fixture.id);
            fixture.succeeded=true;fixture.due();fixture.media.work();
            assertThat(fixture.job().state()).isEqualTo("DISCARDED");assertThat(fixture.cancels).isEqualTo(1);assertThat(fixture.receipts).isZero();
            assertThat(db.queryForObject("SELECT media_generation FROM file_views WHERE file_id=?",UUID.class,fixture.id)).isNull();
            if(!deleted)assertThat(db.queryForObject("SELECT generation FROM file_videos WHERE file_id=?",UUID.class,fixture.id)).isEqualTo(replacement);
        }
    }
    @Test void remoteInputLimitPreservesOriginalWithoutCallingServer() throws Exception {
        try(var fixture=new RemoteMediaFixture(true)) {
            db.update("UPDATE files SET size_bytes=536870913 WHERE id=?",fixture.id);fixture.media.work();
            assertThat(videos().status(fixture.id).state()).isEqualTo("UNSUPPORTED");
            assertThat(videos().status(fixture.id).errorCode()).isEqualTo("FILE_MEDIA_INPUT_LIMIT");assertThat(fixture.creates).isZero();
            assertThat(Files.readAllBytes(store.path(fixture.id))).isEqualTo(bytes);assertThat(service.downloadable(fixture.id).state()).isEqualTo("READY");
        }
    }
    @Test void remoteManifestAndHlsUriTagsCannotEscapeFileStorage() throws Exception {
        try(var fixture=new RemoteMediaFixture(true)) {
            fixture.media.work();var bad=new LinkedHashMap<>(fixture.result());bad.put("files",List.of("../thumbnail.jpg"));
            assertThatThrownBy(()->fixture.media.manifest(fixture.job(),fixture.json.valueToTree(bad))).isInstanceOf(IOException.class);
            fixture.artifacts.put("480p.m3u8","#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"https://evil.invalid/key\"\n480p-00000.ts\n#EXT-X-ENDLIST\n".getBytes());
            fixture.succeeded=true;fixture.due();fixture.media.work();assertThat(fixture.job().state()).isEqualTo("ACTIVE");
            assertThat(fixture.receipts).isZero();assertThat(videos().status(fixture.id).state()).isEqualTo("PROCESSING");
            assertThat(Files.exists(store.mediaOutput(fixture.id,fixture.job().generation(),true))).isFalse();
            assertThat(db.queryForObject("SELECT video_reserved_bytes FROM files WHERE id=?",Long.class,fixture.id)).isZero();
        }
    }
    @Test @EnabledIfEnvironmentVariable(named="NOEDAERI_LIVE_TEST",matches="1")
    @Timeout(300) void realNoedaeriProcessesGeneratedImageAndVideoAndRegistersResults() throws Exception {
        var client=new NoedaeriClient(System.getenv("NOEDAERI_BASE_URL"),System.getenv("NOEDAERI_PLATFORM_API_KEY"),System.getenv("NOEDAERI_PLATFORM_WEBHOOK_SECRET"),536870912);
        var media=new NoedaeriMedia(db,tx,store,service,videos(),org.mockito.Mockito.mock(FileAccess.class),client,new MediaBackend("noedaeri"));
        for(String name:List.of("sample.png","sample.mp4")) {
            byte[] input=Files.readAllBytes(Path.of("/reports/samples",name));
            UUID id=service.create(owner,new FilesService.Create(UUID.randomUUID(),name,(long)input.length,hash(input),"PRIVATE","default")).uploadId();
            service.append(id,owner,0,input.length,hash(input),new ByteArrayInputStream(input));service.complete(id,owner);
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(120);
            while(System.nanoTime()<deadline) {
                db.update("UPDATE file_media_jobs SET next_check_at=now() WHERE file_id=? AND state IN ('NEW','ACTIVE','IMPORTED')",id);
                media.work();
                var states=db.queryForList("SELECT state FROM file_media_jobs WHERE file_id=? AND receipt_at IS NOT NULL",String.class,id);
                if(states.contains("IMPORTED"))break;
                var failure=db.queryForList("SELECT error_code FROM file_media_jobs WHERE file_id=? AND state='FAILED'",String.class,id);
                assertThat(failure).as("live conversion failure").isEmpty();Thread.sleep(2000);
            }
            assertThat(db.queryForObject("SELECT state FROM file_media_jobs WHERE file_id=? AND receipt_at IS NOT NULL",String.class,id)).isEqualTo("IMPORTED");
            assertThat(Files.size(store.thumbnail(id))).isPositive();assertThat(Files.readAllBytes(store.path(id))).isEqualTo(input);
            code("FILE_NOT_FOUND",()->views().authorize(id,null,null));
            if(name.endsWith(".png")){assertThat(Files.size(store.preview(id))).isPositive();assertThat(views().view(id).kind()).isEqualTo("IMAGE");}
            else {
                assertThat(videos().status(id).variants()).extracting(FileVideos.Variant::quality).containsExactly(480,720,1080);
                for(var variant:videos().status(id).variants())assertThat(videos().playlist(id,variant.playlist(),"capability")).contains("?token=capability");
            }
            service.delete(id,owner);service.cleanup();assertThat(Files.exists(store.path(id))).isFalse();assertThat(Files.exists(store.media(id))).isFalse();assertThat(Files.exists(store.video(id))).isFalse();
        }
    }
    FileShares shares(){return new FileShares(db,tx,service,org.mockito.Mockito.mock(FileAccess.class),views());}
    @Test void publicShareMetadataIsScopedValidatedRevisionCheckedAndInvalidatedWithVisibility() {
        UUID id=duplicateFixture(owner,"공개 문서.txt",bytes,"PUBLIC");var shares=new FilePublicShares(db,tx,service);
        assertThat(shares.get(id,owner).revision()).isZero();
        var input=new FilePublicShares.Settings("공유 제목","설명",false,0L);
        var foreign=new FileAccess.Context(owner.projectId(),UUID.randomUUID(),UUID.randomUUID());
        code("FILE_NOT_FOUND",()->shares.save(id,foreign,input));
        assertThat(shares.save(id,owner,input).revision()).isEqualTo(1);
        code("FILE_SHARE_METADATA_CHANGED",()->shares.save(id,owner,input));
        code("FILE_SHARE_METADATA_INVALID",()->shares.save(id,owner,new FilePublicShares.Settings("x".repeat(121),"",true,1L)));
        assertThat(views().links(id,null,null).shareUrl()).endsWith("/share");
        service.visibility(id,owner,"PRIVATE");
        assertThat(views().links(id,null,null).shareUrl()).isNull();
        code("FILE_PUBLIC_REQUIRED",()->shares.save(id,owner,new FilePublicShares.Settings("","",true,1L)));
        code("FILE_NOT_FOUND",()->views().authorize(id,null,null));
        assertThat(db.queryForObject("SELECT count(*) FROM file_audit WHERE file_id=? AND action='file.public-share.updated'",Integer.class,id)).isEqualTo(1);
    }
    @Test void passwordSharesArePrivateScopedHashedAndRevocableAcrossAllViews() {
        UUID id=duplicateFixture(owner,"secret.txt",bytes,"PUBLIC");var shares=shares();String password="공유 검증 비밀번호";
        code("FILE_SHARE_PRIVATE_REQUIRED",()->shares.create(id,owner,new FileShares.Create(password,7)));
        service.visibility(id,owner,"PRIVATE");
        var foreign=new FileAccess.Context(owner.projectId(),UUID.randomUUID(),UUID.randomUUID());
        code("FILE_NOT_FOUND",()->shares.create(id,foreign,new FileShares.Create(password,7)));
        var share=shares.create(id,owner,new FileShares.Create(password,null));
        assertThat(share.state()).isEqualTo("ACTIVE");assertThat(share.expiresAt()).isAfter(java.time.Instant.now().plusSeconds(6*86400));
        assertThat(db.queryForObject("SELECT password_hash FROM file_shares WHERE id=?",String.class,share.shareId())).doesNotContain(password).hasSize(96);
        code("FILE_NOT_FOUND",()->shares.list(id,foreign));code("FILE_NOT_FOUND",()->shares.revoke(id,share.shareId(),foreign));
        code("FILE_SHARE_PASSWORD_INVALID",()->shares.unlock(share.shareId(),"wrong-password"));
        assertThat(db.queryForObject("SELECT attempts FROM file_shares WHERE id=?",Integer.class,share.shareId())).isEqualTo(1);
        var links=shares.unlock(share.shareId(),password);String token=links.viewerUrl().split("token=")[1];
        assertThat(views().authorize(id,token,null).id()).isEqualTo(id);assertThat(views().authorize(id,token,null,true).id()).isEqualTo(id);
        assertThat(db.queryForObject("SELECT token_hash FROM file_view_tokens WHERE share_id=?",String.class,share.shareId())).isNotEqualTo(token);
        shares.revoke(id,share.shareId(),owner);shares.revoke(id,share.shareId(),owner);
        assertThat(shares.list(id,owner).getFirst().state()).isEqualTo("REVOKED");
        code("FILE_SHARE_UNAVAILABLE",()->shares.unlock(share.shareId(),password));
        code("FILE_NOT_FOUND",()->views().authorize(id,token,null));code("FILE_NOT_FOUND",()->views().authorize(id,token,null,true));
        assertThat(db.queryForObject("SELECT count(*) FROM file_audit WHERE share_id=? AND action='file.share.revoked'",Long.class,share.shareId())).isEqualTo(1);
    }
    @Test void shareAttemptsSurviveFailureAndRestartAndResetAfterWindow() {
        UUID id=duplicateFixture(owner,"secret.txt",bytes,"PRIVATE");var shares=shares();var share=shares.create(id,owner,new FileShares.Create("password-123",1));
        for(int i=0;i<10;i++)code("FILE_SHARE_PASSWORD_INVALID",()->shares.unlock(share.shareId(),"wrong-password"));
        code("FILE_SHARE_RATE_LIMITED",()->shares().unlock(share.shareId(),"password-123"));
        db.update("UPDATE file_shares SET window_started_at=now()-interval '16 minutes' WHERE id=?",share.shareId());
        assertThat(shares().unlock(share.shareId(),"password-123").fileId()).isEqualTo(id);
        assertThat(db.queryForObject("SELECT attempts FROM file_shares WHERE id=?",Integer.class,share.shareId())).isEqualTo(1);
    }
    @Test void shareExpiryVisibilityDeletionAndProjectStopInvalidateAccess() {
        UUID id=duplicateFixture(owner,"secret.mp4",bytes,"PRIVATE");var shares=shares();var share=shares.create(id,owner,new FileShares.Create("password-123",1));
        db.update("UPDATE file_shares SET expires_at=now()+interval '60 seconds' WHERE id=?",share.shareId());
        var links=shares.unlock(share.shareId(),"password-123");String token=links.viewerUrl().split("token=")[1];
        assertThat(links.streamExpiresAt()).isEqualTo(links.expiresAt()).isBefore(java.time.Instant.now().plusSeconds(61));
        db.update("UPDATE file_shares SET expires_at=now()-interval '1 second' WHERE id=?",share.shareId());
        code("FILE_NOT_FOUND",()->views().authorize(id,token,null,true));code("FILE_SHARE_UNAVAILABLE",()->shares.available(share.shareId()));
        var next=shares.create(id,owner,new FileShares.Create("password-123",7));
        String old=shares.unlock(next.shareId(),"password-123").viewerUrl().split("token=")[1];service.visibility(id,owner,"PUBLIC");
        code("FILE_NOT_FOUND",()->views().authorize(id,old,null,true));assertThat(views().authorize(id,null,null).id()).isEqualTo(id);
        service.visibility(id,owner,"PRIVATE");
        code("FILE_SHARE_UNAVAILABLE",()->shares.unlock(next.shareId(),"password-123"));
        assertThat(shares.list(id,owner).getFirst().state()).isEqualTo("INVALIDATED");
        var active=shares.create(id,owner,new FileShares.Create("password-123",7));
        var access=org.mockito.Mockito.mock(FileAccess.class);org.mockito.Mockito.doThrow(FileFailure.missing()).when(access).requireActive(owner.environmentId());
        var stopped=new FileShares(db,tx,service,access,views());code("FILE_NOT_FOUND",()->stopped.unlock(active.shareId(),"password-123"));
        service.delete(id,owner);code("FILE_SHARE_UNAVAILABLE",()->shares.available(active.shareId()));
    }
    @Test void shareValidationLimitsAndAuditFailureRollback() {
        UUID id=duplicateFixture(owner,"secret.txt",bytes,"PRIVATE");var shares=shares();
        code("FILE_SHARE_INVALID_PASSWORD",()->shares.create(id,owner,new FileShares.Create("short",7)));
        code("FILE_SHARE_INVALID_PASSWORD",()->shares.create(id,owner,new FileShares.Create(" ".repeat(8),7)));
        code("FILE_SHARE_INVALID_EXPIRY",()->shares.create(id,owner,new FileShares.Create("password-123",31)));
        db.execute("ALTER TABLE file_audit ADD CONSTRAINT fail_share_audit CHECK (action<>'file.share.created')");
        assertThatThrownBy(()->shares.create(id,owner,new FileShares.Create("password-123",7))).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(shares.list(id,owner)).isEmpty();db.execute("ALTER TABLE file_audit DROP CONSTRAINT fail_share_audit");
        for(int i=0;i<10;i++)shares.create(id,owner,new FileShares.Create("password-123",7));
        code("FILE_SHARE_LIMIT",()->shares.create(id,owner,new FileShares.Create("password-123",7)));
        shares.revoke(id,shares.list(id,owner).getFirst().shareId(),owner);
        assertThat(shares.create(id,owner,new FileShares.Create("password-123",7)).state()).isEqualTo("ACTIVE");
    }
    UUID duplicateFixture(FileAccess.Context context,String name,byte[] content,String visibility) {
        UUID id=service.create(context,new FilesService.Create(UUID.randomUUID(),name,(long)content.length,hash(content),visibility,"default")).uploadId();
        if(content.length>0)service.append(id,context,0,content.length,hash(content),new ByteArrayInputStream(content));
        service.complete(id,context);return id;
    }
    @Test void datedStorageSurvivesRestartAndRemovesOnlyItsOwnOriginalAndDerivatives() throws Exception {
        UUID id = duplicateFixture(owner, "사진.PNG", bytes, "PUBLIC");
        UUID neighbor = duplicateFixture(owner, "다른 사진.PNG", bytes, "PUBLIC");
        var created = db.queryForObject("SELECT created_at FROM files WHERE id=?", java.sql.Timestamp.class, id).toInstant();
        String key = FileStore.storagePath(id, owner.projectId(), owner.environmentId(), "사진.PNG", created);
        assertThat(db.queryForObject("SELECT storage_path FROM files WHERE id=?", String.class, id)).isEqualTo(key);
        assertThat(store.path(id)).isEqualTo(directory.resolve(key));
        Files.write(store.thumbnail(id), new byte[]{1, 2});
        Files.write(store.preview(id), new byte[]{3, 4});
        Files.write(store.previewTemporary(id), new byte[]{5}); // Simulate an interrupted conversion.
        Path segments = store.video(id).resolve(UUID.randomUUID().toString());
        Files.createDirectories(segments); Files.writeString(segments.resolve("q360-00000.ts"), "segment");
        var restarted = new FileStore(directory.toString(), db);
        try (var input = restarted.open(id)) { assertThat(input.readAllBytes()).isEqualTo(bytes); }
        assertThat(restarted.thumbnail(id)).isEqualTo(store.path(id).resolveSibling("thumbnail.jpg"));
        assertThat(restarted.preview(id)).isEqualTo(store.path(id).resolveSibling("preview.webp"));
        assertThat(restarted.video(id)).isEqualTo(store.path(id).resolveSibling("hls"));
        service.delete(id, owner); service.cleanup();
        assertThat(store.path(id).getParent()).doesNotExist();
        assertThat(Files.readAllBytes(store.path(neighbor))).isEqualTo(bytes);
        assertThat(db.queryForObject("SELECT purged_at IS NOT NULL FROM files WHERE id=?", Boolean.class, id)).isTrue();
    }
    @Test void existingImagesAreQueuedForSeparatePreviewWithoutChangingOtherFiles() throws Exception {
        UUID image=duplicateFixture(owner,"old.png",bytes,"PUBLIC"),text=duplicateFixture(owner,"note.txt",bytes,"PUBLIC");
        var views=views();views.view(image);views.view(text);
        db.update("UPDATE file_views SET state='READY',kind='IMAGE',media_type='image/png',thumbnail=true,attempts=2 WHERE file_id=?",image);
        db.update("UPDATE file_views SET state='READY',kind='TEXT',media_type='text/plain' WHERE file_id=?",text);
        try(var sql=getClass().getResourceAsStream("/db/migration/V10__image_previews.sql")) {
            db.execute(new String(sql.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));
        }
        assertThat(views.view(image).state()).isEqualTo("QUEUED");
        assertThat(views.view(image).thumbnail()).isTrue();
        assertThat(views.links(image,null,null).previewUrl()).endsWith("/content/preview");
        assertThat(db.queryForObject("SELECT attempts FROM file_views WHERE file_id=?",Integer.class,image)).isZero();
        assertThat(views.view(text).state()).isEqualTo("READY");
        assertThat(Files.readAllBytes(store.path(image))).isEqualTo(bytes);
    }
    @Test void legacyFlatUploadCanResumeAndKeepThumbnailAndHlsPathsAfterUpgrade() throws Exception {
        UUID id = create().uploadId();
        // V9 leaves all pre-upgrade rows NULL, including uploads with received bytes.
        db.update("UPDATE files SET storage_path=NULL,received_bytes=5 WHERE id=?", id);
        Files.write(directory.resolve(id + ".bin"), Arrays.copyOfRange(bytes, 0, 5));
        store = new FileStore(directory.toString(), db);
        service = new FilesService(db, tx, store, 10_000_000, 20);
        append(id, 5, Arrays.copyOfRange(bytes, 5, bytes.length)); service.complete(id, owner);
        assertThat(Files.readAllBytes(store.path(id))).isEqualTo(bytes);
        assertThat(store.path(id)).isEqualTo(directory.resolve(id + ".bin"));
        assertThat(store.thumbnail(id)).isEqualTo(directory.resolve(id + ".jpg"));
        assertThat(store.video(id)).isEqualTo(directory.resolve(id + ".hls"));
        Files.write(store.thumbnail(id), new byte[]{1});
        assertThat(store.preview(id)).isEqualTo(directory.resolve(id + ".preview.webp"));
        Files.write(store.preview(id), new byte[]{2});
        Files.write(store.previewTemporary(id), new byte[]{3});
        Files.createDirectories(store.video(id)); Files.writeString(store.video(id).resolve("master.m3u8"), "playlist");
        service.delete(id, owner); service.cleanup();
        assertThat(store.path(id)).doesNotExist(); assertThat(store.thumbnail(id)).doesNotExist();
        assertThat(store.preview(id)).doesNotExist(); assertThat(store.previewTemporary(id)).doesNotExist();
        assertThat(store.video(id)).doesNotExist(); assertThat(directory).isDirectory();
    }
    @Test void emptyFileCreatesDatedDirectoryAndPersistedPathCannotEscapeStorageRoot() throws Exception {
        UUID id = duplicateFixture(owner, "empty.txt", new byte[0], "PRIVATE");
        assertThat(Files.size(store.path(id))).isZero();
        for (String invalid : List.of("../" + id + "/original.txt", "/tmp/" + id + "/original.txt", "..\\" + id + "\\original.txt")) {
            db.update("UPDATE files SET storage_path=? WHERE id=?", invalid, id);
            assertThatThrownBy(() -> store.path(id)).isInstanceOf(IllegalStateException.class);
        }
    }
    @Test void duplicatesUseVerifiedBytesExcludeSelfAndNeverMergeFilePolicies() {
        UUID source=duplicateFixture(owner,"original.txt",bytes,"PUBLIC");
        UUID renamed=duplicateFixture(owner,"renamed.txt",bytes,"PRIVATE");
        service.retention(renamed,owner,"영구","default");
        duplicateFixture(owner,"original.txt",new byte[bytes.length],"PUBLIC");
        UUID unverified=service.create(owner,input(bytes)).uploadId();
        byte[] wrong=new byte[bytes.length];service.append(unverified,owner,0,wrong.length,hash(wrong),new ByteArrayInputStream(wrong));
        code("FILE_CHECKSUM_MISMATCH",()->service.complete(unverified,owner));
        code("FILE_NOT_FOUND",()->service.duplicates(unverified,owner,20,0));
        var before=service.detail(source,owner);long audits=db.queryForObject("SELECT count(*) FROM file_audit",Long.class);
        var found=service.duplicates(source,owner,20,0);
        assertThat(found.fileId()).isEqualTo(source);assertThat(found.hasMore()).isFalse();
        assertThat(found.files()).extracting(FilesService.FileInfo::fileId).containsExactly(renamed);
        assertThat(found.files().getFirst().visibility()).isEqualTo("PRIVATE");assertThat(found.files().getFirst().retentionCode()).isEqualTo("영구");
        assertThat(service.detail(source,owner)).isEqualTo(before);
        assertThat(db.queryForObject("SELECT count(*) FROM file_audit",Long.class)).isEqualTo(audits);
        service.delete(renamed,owner);assertThat(service.duplicates(source,owner,20,0).files()).isEmpty();
        service.delete(source,owner);code("FILE_NOT_FOUND",()->service.duplicates(source,owner,20,0));
    }
    @Test void duplicateLookupIsBoundToBothProjectAndEnvironment() {
        UUID source=duplicateFixture(owner,"source.txt",bytes,"PRIVATE");
        UUID match=duplicateFixture(owner,"match.txt",bytes,"PUBLIC");
        var prod=new FileAccess.Context(owner.projectId(),UUID.randomUUID(),UUID.randomUUID());
        var foreign=new FileAccess.Context(UUID.randomUUID(),owner.environmentId(),UUID.randomUUID());
        duplicateFixture(prod,"prod.txt",bytes,"PRIVATE");duplicateFixture(foreign,"foreign.txt",bytes,"PUBLIC");
        assertThat(service.duplicates(source,owner,20,0).files()).extracting(FilesService.FileInfo::fileId).containsExactly(match);
        code("FILE_NOT_FOUND",()->service.duplicates(source,prod,20,0));code("FILE_NOT_FOUND",()->service.duplicates(source,foreign,20,0));
        var authorized=new FileAccess.Context(owner.projectId(),owner.environmentId(),UUID.randomUUID());
        assertThat(service.duplicates(source,authorized,20,0).files()).hasSize(1);
    }
    @Test void duplicateLookupPaginatesVerifiedEmptyFilesAndValidatesBounds() {
        UUID source=duplicateFixture(owner,"empty.txt",new byte[0],"PUBLIC");
        for(int i=0;i<3;i++)duplicateFixture(owner,"empty-"+i,new byte[0],"PUBLIC");
        var first=service.duplicates(source,owner,2,0);var next=service.duplicates(source,owner,2,2);
        assertThat(first.files()).hasSize(2);assertThat(first.hasMore()).isTrue();assertThat(next.files()).hasSize(1);assertThat(next.hasMore()).isFalse();
        assertThat(first.files()).doesNotContainAnyElementsOf(next.files());
        assertThat(service.duplicates(source,owner,2,20).files()).isEmpty();
        assertThatThrownBy(()->service.duplicates(source,owner,0,0)).isInstanceOf(FileFailure.class);
        assertThatThrownBy(()->service.duplicates(source,owner,101,0)).isInstanceOf(FileFailure.class);
        assertThatThrownBy(()->service.duplicates(source,owner,5,-1)).isInstanceOf(FileFailure.class);
    }
    @Test void videoQualityPreservesPortraitAndNeverUpscales() {
        var landscape=FileVideos.variants(new FileVideos.Source(1920,1080,12,true));
        assertThat(landscape).extracting(FileVideos.Variant::quality).containsExactly(480,720,1080);
        var portrait=FileVideos.variants(new FileVideos.Source(720,1280,12,false));
        assertThat(portrait).extracting(FileVideos.Variant::quality).containsExactly(480,720);
        assertThat(portrait.getFirst().width()).isEqualTo(480);assertThat(portrait.getFirst().height()).isEqualTo(852);
        var below480=FileVideos.variants(new FileVideos.Source(640,360,12,true));
        assertThat(below480).extracting(FileVideos.Variant::quality).containsExactly(360);
        assertThat(below480.getFirst().width()).isEqualTo(640);assertThat(below480.getFirst().height()).isEqualTo(360);
        var small=FileVideos.variants(new FileVideos.Source(321,241,12,false)).getFirst();
        assertThat(small.quality()).isEqualTo(240);assertThat(small.width()).isLessThanOrEqualTo(321);assertThat(small.height()).isLessThanOrEqualTo(241);
    }
    UUID videoFile() {
        UUID id=create().uploadId();append(id,0,bytes);service.complete(id,owner);
        db.update("UPDATE files SET original_name='video.mp4' WHERE id=?",id);return id;
    }
    @Test void playbackCapabilityOutlivesViewTokenButCannotReadOriginalAndIsRevoked() {
        UUID id=videoFile();service.visibility(id,owner,"PRIVATE");var v=views();var links=v.manage(id,owner,java.time.Instant.now().plusSeconds(60));
        String token=links.viewerUrl().split("token=")[1];assertThat(links.streamExpiresAt()).isAfter(links.expiresAt().plusSeconds(7000));
        db.update("UPDATE file_view_tokens SET expires_at=now()-interval '1 second' WHERE file_id=?",id);
        code("FILE_NOT_FOUND",()->v.authorize(id,token,null));assertThat(v.authorize(id,token,null,true).id()).isEqualTo(id);
        db.update("UPDATE file_view_tokens SET playback_expires_at=now()-interval '1 second' WHERE file_id=?",id);
        code("FILE_NOT_FOUND",()->v.authorize(id,token,null,true));
        String next=v.manage(id,owner,null).viewerUrl().split("token=")[1];service.visibility(id,owner,"PUBLIC");service.visibility(id,owner,"PRIVATE");
        code("FILE_NOT_FOUND",()->v.authorize(id,next,null,true));
    }
    @Test void playlistRewritesEveryChildWithCapabilityAndRejectsForeignPaths() throws Exception {
        UUID id=videoFile(),generation=UUID.randomUUID();Path directory=store.video(id).resolve(generation.toString());Files.createDirectories(directory);
        db.update("INSERT INTO file_videos(file_id,state,generation) VALUES (?,'READY',?)",id,generation);
        Files.writeString(directory.resolve("master.m3u8"),"#EXTM3U\nq360.m3u8\n");
        Files.writeString(directory.resolve("q360.m3u8"),"#EXTM3U\n#EXTINF:6,\nq360-00000.ts\n#EXT-X-ENDLIST\n");
        assertThat(videos().playlist(id,"master.m3u8","capability")).contains("/hls/q360.m3u8?token=capability");
        assertThat(videos().playlist(id,"q360.m3u8","capability")).contains("/hls/q360-00000.ts?token=capability");
        code("FILE_NOT_FOUND",()->videos().asset(id,"../secret"));code("FILE_NOT_FOUND",()->videos().asset(id,"q720.m3u8"));
        Files.writeString(directory.resolve("q360.m3u8"),"#EXTM3U\nhttps://evil.invalid/segment.ts\n");
        assertThatThrownBy(()->videos().playlist(id,"q360.m3u8",null)).isInstanceOf(FileFailure.class);
    }
    @Test void noedaeriAndLegacyPlaylistsCoexistAndRejectUntrustedNames() throws Exception {
        UUID id=videoFile(),generation=UUID.randomUUID();Path directory=store.video(id).resolve(generation.toString());Files.createDirectories(directory);
        db.update("INSERT INTO file_videos(file_id,state,generation) VALUES (?,'READY',?)",id,generation);
        var videos=videos();
        Files.writeString(directory.resolve("master.m3u8"),"#EXTM3U\nq360.m3u8\n480p.m3u8\n720p.m3u8\n1080p.m3u8\n");
        assertThat(videos.playlist(id,"master.m3u8","capability")).contains("/hls/q360.m3u8?token=capability");
        for(String label:List.of("480p","720p","1080p")) {
            Files.writeString(directory.resolve(label+".m3u8"),"#EXTM3U\n#EXTINF:6,\n"+label+"-00000.ts\n#EXT-X-ENDLIST\n");
            Files.writeString(directory.resolve(label+"-00000.ts"),"segment");
            assertThat(videos.playlist(id,"master.m3u8","capability")).contains("/hls/"+label+".m3u8?token=capability");
            assertThat(videos.playlist(id,label+".m3u8","capability")).contains("/hls/"+label+"-00000.ts?token=capability");
            assertThat(videos.playlist(id,label+".m3u8",null)).contains("/hls/"+label+"-00000.ts\n").doesNotContain("?token=");
            assertThat(videos.asset(id,label+"-00000.ts")).isEqualTo(directory.resolve(label+"-00000.ts"));
        }
        code("FILE_NOT_FOUND",()->videos.asset(id,"240p.m3u8"));
        for(String name:List.of("../480p-00000.ts","sub/480p-00000.ts","sub\\480p-00000.ts","%2e%2e%2f480p-00000.ts",
            "https://evil.invalid/480p-00000.ts","//evil.invalid/480p-00000.ts","480p-00000.ts?token=x","480p-00000.ts#x",
            "480p-0000.ts","480p-000000.ts","480p.m3u8.exe","thumbnail.jpg")) {
            code("FILE_NOT_FOUND",()->videos.asset(id,name));
            Files.writeString(directory.resolve("480p.m3u8"),"#EXTM3U\n"+name+"\n");
            assertThatThrownBy(()->videos.playlist(id,"480p.m3u8",null)).isInstanceOf(FileFailure.class);
        }
    }
    @Test void derivativeReservationsCountAgainstUploadsAndSurviveUntilRecoveryCleanup() throws Exception {
        UUID id=videoFile();videos().reserve(service.downloadable(id),9_999_900);
        code("FILE_QUOTA_EXCEEDED",()->service.create(owner,new FilesService.Create(UUID.randomUUID(),"more.bin",1000L,hash(bytes),null,null)));
        code("FILE_QUOTA_EXCEEDED",()->videos().reserve(service.downloadable(id),1000));
        Path directory=store.video(id).resolve(UUID.randomUUID().toString());Files.createDirectories(directory);Files.writeString(directory.resolve("partial.ts"),"partial");
        db.update("INSERT INTO file_videos(file_id,state,attempts,heartbeat_at) VALUES (?,'PROCESSING',2,now()-interval '3 minutes')",id);
        videos().recover();assertThat(Files.exists(store.video(id))).isFalse();assertThat(videos().status(id).state()).isEqualTo("QUEUED");
        assertThat(db.queryForObject("SELECT video_reserved_bytes FROM files WHERE id=?",Long.class,id)).isZero();
        db.update("UPDATE file_videos SET state='PROCESSING',attempts=3,heartbeat_at=now()-interval '3 minutes' WHERE file_id=?",id);
        videos().recover();assertThat(videos().status(id).state()).isEqualTo("FAILED");
        var foreign=new FileAccess.Context(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID());code("FILE_NOT_FOUND",()->videos().retry(id,foreign));
        assertThat(videos().retry(id,owner).state()).isEqualTo("QUEUED");
    }
    @Test void activeConversionDelaysPhysicalDeletionThenPurgesAllDerivatives() throws Exception {
        UUID id=videoFile();Path directory=store.video(id).resolve(UUID.randomUUID().toString());Files.createDirectories(directory);Files.writeString(directory.resolve("q360-00000.ts"),"segment");
        db.update("INSERT INTO file_videos(file_id,state,heartbeat_at) VALUES (?,'PROCESSING',now())",id);
        service.delete(id,owner);service.cleanup();assertThat(Files.exists(store.video(id))).isTrue();
        db.update("UPDATE file_videos SET state='FAILED' WHERE file_id=?",id);service.cleanup();assertThat(Files.exists(store.video(id))).isFalse();
        assertThat(Files.exists(store.path(id))).isFalse();
    }
    @Test void privateViewLinksExpireAndAllVariantsFollowVisibilityAndDeletion() {
        UUID id=readyOld();var views=views();service.visibility(id,owner,"PRIVATE");
        var links=views.manage(id,owner,java.time.Instant.now().plusSeconds(45));
        String token=links.originalUrl().split("token=")[1];
        assertThat(links.expiresAt()).isBefore(java.time.Instant.now().plusSeconds(46));
        code("FILE_NOT_FOUND",()->views.authorize(id,null,null));
        assertThat(views.authorize(id,token,null).id()).isEqualTo(id);
        assertThat(views.authorize(id,token,null).id()).isEqualTo(id); // media range requests reuse the bounded viewer token
        service.visibility(id,owner,"PUBLIC");service.visibility(id,owner,"PRIVATE");
        code("FILE_NOT_FOUND",()->views.authorize(id,token,null));
        String next=views.manage(id,owner,null).originalUrl().split("token=")[1];
        db.update("UPDATE file_view_tokens SET expires_at=now()-interval '1 second'");
        code("FILE_NOT_FOUND",()->views.authorize(id,next,null));
        String last=views.manage(id,owner,null).originalUrl().split("token=")[1];service.delete(id,owner);
        code("FILE_NOT_FOUND",()->views.authorize(id,last,null));
    }
    @Test void previewWorkerHandlesExistingFilesAndDoesNotCountAsContentUse() {
        var input=new FilesService.Create(UUID.randomUUID(),"safe.md",(long)bytes.length,hash(bytes),"PRIVATE","default");
        UUID id=service.create(owner,input).uploadId();append(id,0,bytes);service.complete(id,owner);
        var before=service.detail(id,owner).lastUsedAt();var views=views();views.work();
        assertThat(views.view(id).state()).isEqualTo("READY");assertThat(views.view(id).kind()).isEqualTo("MARKDOWN");
        assertThat(service.detail(id,owner).lastUsedAt()).isEqualTo(before);
        var foreign=new FileAccess.Context(owner.projectId(),UUID.randomUUID(),owner.credentialId());
        code("FILE_NOT_FOUND",()->views.manage(id,foreign,null));
        db.update("UPDATE file_views SET state='PROCESSING',started_at=now()-interval '3 minutes',attempts=1 WHERE file_id=?",id);
        views.work();assertThat(views.view(id).state()).isEqualTo("READY");
        db.update("UPDATE file_views SET state='PROCESSING',started_at=now()-interval '3 minutes',attempts=3 WHERE file_id=?",id);
        views.work();assertThat(views.view(id).state()).isEqualTo("FAILED");
        views.retry(id,owner);views.work();assertThat(views.view(id).state()).isEqualTo("READY");
    }
    @Test void unsupportedAndCorruptTextKeepOriginalWithoutRenderingExecutableContent() {
        UUID html=create().uploadId();append(html,0,bytes);service.complete(html,owner);views().work();
        assertThat(views().view(html).state()).isEqualTo("UNSUPPORTED");
        String safe=FileViewsController.markdown("# 제목\n<script>alert(1)</script>\n\n[x](javascript:alert(1))");
        assertThat(safe).contains("<h1>제목</h1>","&lt;script&gt;").doesNotContain("<script>","href=\"javascript:");
        assertThat(service.downloadable(html).state()).isEqualTo("READY");
    }
    UUID readyOld() {
        UUID id=create().uploadId();append(id,0,bytes);service.complete(id,owner);
        db.update("UPDATE files SET last_used_at=now()-interval '2 years' WHERE id=?",id);return id;
    }
    void enableRetention(RetentionService retention) {
        var overview=retention.overview(owner);
        retention.saveSettings(owner,new RetentionService.SaveSettings(true,7,overview.settings().revision(),overview.eligibleFiles()));
    }
    @Test void calendarPeriodsUseUtcMonthEndAndLeapYears() {
        assertThat(db.queryForObject("SELECT file_retention_due('2024-02-29 12:30:00+00',1,'YEAR')::text",String.class)).startsWith("2025-02-28 12:30:00");
        assertThat(db.queryForObject("SELECT file_retention_due('2024-01-31 00:00:00+00',1,'MONTH')::text",String.class)).startsWith("2024-02-29 00:00:00");
        assertThat(db.queryForObject("SELECT file_retention_due(now(),NULL,'FOREVER')",java.sql.Timestamp.class)).isNull();
        tx.executeWithoutResult(s->{db.execute("SET LOCAL TIME ZONE 'America/New_York'");
            assertThat(db.queryForObject("SELECT extract(epoch FROM file_retention_due('2024-03-09 12:00:00+00',1,'DAY')-'2024-03-09 12:00:00+00'::timestamptz)",Long.class)).isEqualTo(86400);});
    }
    @Test void retentionIsOptInAndGraceSurvivesRestartBeforePhysicalPurge() {
        UUID id=readyOld();var r=retention();assertThat(r.overview(owner).settings().enabled()).isFalse();
        r.sweepEnvironment(owner.environmentId());assertThat(service.downloadable(id).state()).isEqualTo("READY");
        enableRetention(r);r.sweepEnvironment(owner.environmentId());
        assertThat(r.candidates(owner,0).getFirst().deleteAfter()).isNotNull();
        r.sweepEnvironment(owner.environmentId());assertThat(service.downloadable(id).state()).isEqualTo("READY");
        db.update("UPDATE files SET retention_marked_at=now()-interval '8 days' WHERE id=?",id);
        retention().sweepEnvironment(owner.environmentId());code("FILE_NOT_FOUND",()->service.downloadable(id));
        assertThat(Files.exists(store.path(id))).isTrue();service.cleanup();assertThat(Files.exists(store.path(id))).isFalse();
        assertThat(db.queryForObject("SELECT count(*) FROM file_audit WHERE file_id=? AND action='file.retention.deleted'",Long.class,id)).isEqualTo(1);
    }
    @Test void policyExtensionAndPermanentChangeRescueExistingFilesAndDoNotBreakUploadIdempotency() {
        var input=input(bytes);UUID id=service.create(owner,input).uploadId();append(id,0,bytes);service.complete(id,owner);
        db.update("UPDATE files SET last_used_at=now()-interval '2 years' WHERE id=?",id);
        var r=retention();enableRetention(r);r.sweepEnvironment(owner.environmentId());
        var change=new RetentionService.Policy("default","기본",3,"YEAR",true,1);
        assertThat(r.preview(owner,change).eligibleFiles()).isZero();r.savePolicy(owner,new RetentionService.SavePolicy(change,0));
        assertThat(r.candidates(owner,0)).isEmpty();r.sweepEnvironment(owner.environmentId());
        service.retention(id,owner,"영구","default");
        assertThat(service.create(owner,input).uploadId()).isEqualTo(id);
        assertThat(service.detail(id,owner).retentionCode()).isEqualTo("영구");
        code("FILE_RETENTION_CONFLICT",()->service.retention(id,owner,"tmp","default"));
        db.update("UPDATE files SET last_used_at=now()-interval '100 years' WHERE id=?",id);
        r.sweepEnvironment(owner.environmentId());assertThat(r.candidates(owner,0)).isEmpty();
        service.delete(id,owner);code("FILE_NOT_FOUND",()->service.downloadable(id));
    }
    @Test void previewRevisionCountsAndAuditFailureProtectPolicyChanges() {
        var r=retention();r.overview(owner);var p=new RetentionService.Policy("default","수정",1,"DAY",true,1);
        assertThat(r.preview(owner,p).eligibleFiles()).isZero();readyOld();
        code("FILE_RETENTION_CONFLICT",()->r.savePolicy(owner,new RetentionService.SavePolicy(p,0)));
        db.execute("ALTER TABLE file_retention_audit ADD CONSTRAINT reject_policy CHECK(action<>'retention.policy.saved')");
        assertThatThrownBy(()->r.savePolicy(owner,new RetentionService.SavePolicy(p,1))).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(r.overview(owner).policies().getFirst().periodUnit()).isEqualTo("YEAR");
        db.execute("ALTER TABLE file_retention_audit DROP CONSTRAINT reject_policy");
        r.savePolicy(owner,new RetentionService.SavePolicy(p,1));
        code("FILE_RETENTION_CONFLICT",()->r.savePolicy(owner,new RetentionService.SavePolicy(p,1)));
        assertThat(r.history(owner)).hasSize(1);
    }
    @Test void disabledCustomCodesRejectNewUploadsButKeepExistingReferences() {
        var r=retention();r.savePolicy(owner,new RetentionService.SavePolicy(new RetentionService.Policy("archive","자료",2,"MONTH",true,0),0));
        var input=new FilesService.Create(UUID.randomUUID(),"old.txt",0L,hash(new byte[0]),null,"archive");
        UUID id=service.create(owner,input).uploadId();service.complete(id,owner);
        r.savePolicy(owner,new RetentionService.SavePolicy(new RetentionService.Policy("archive","자료",2,"MONTH",false,1),0));
        assertThat(service.create(owner,input).uploadId()).isEqualTo(id);
        code("FILE_RETENTION_UNKNOWN",()->service.create(owner,new FilesService.Create(UUID.randomUUID(),"new.txt",0L,hash(new byte[0]),null,"archive")));
        assertThat(service.detail(id,owner).retentionCode()).isEqualTo("archive");
        code("INVALID_REQUEST",()->r.preview(owner,new RetentionService.Policy("영구","영구",1,"DAY",true,1)));
    }
    @Test void activeDownloadsBlockRetentionAndOnlySuccessfulContentResetsUsage() {
        UUID id=readyOld();var r=retention();enableRetention(r);r.sweepEnvironment(owner.environmentId());
        db.update("UPDATE files SET retention_marked_at=now()-interval '8 days' WHERE id=?",id);
        var row=service.downloadable(id);var old=row.used();UUID lease=service.beginDownload(row);
        r.sweepEnvironment(owner.environmentId());assertThat(service.downloadable(id).state()).isEqualTo("READY");
        service.heartbeatDownloads();service.finishDownload(lease,row,false);
        assertThat(service.detail(id,owner).lastUsedAt()).isEqualTo(old);
        lease=service.beginDownload(row);service.finishDownload(lease,row,true);
        r.sweepEnvironment(owner.environmentId());assertThat(r.candidates(owner,0)).isEmpty();
        assertThat(service.detail(id,owner).lastUsedAt()).isAfter(old);
    }
    @Test void expiryAuditFailureRollsBackAndPausedCleanupRetainsFiles() {
        UUID id=readyOld();var r=retention();enableRetention(r);r.sweepEnvironment(owner.environmentId());
        db.update("UPDATE files SET retention_marked_at=now()-interval '8 days' WHERE id=?",id);
        db.execute("ALTER TABLE file_audit ADD CONSTRAINT reject_expiry CHECK(action<>'file.retention.deleted')");
        assertThatThrownBy(()->r.sweepEnvironment(owner.environmentId())).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(service.downloadable(id).state()).isEqualTo("READY");
        var overview=r.overview(owner);r.saveSettings(owner,new RetentionService.SaveSettings(false,7,overview.settings().revision(),overview.eligibleFiles()));
        r.sweepEnvironment(owner.environmentId());assertThat(service.downloadable(id).state()).isEqualTo("READY");
    }
    FilesService.Create input(byte[] content) { return new FilesService.Create(UUID.randomUUID(), "검증.html", (long) content.length, hash(content), null, null); }
    FilesService.Upload create() { return service.create(owner, input(bytes)); }
    void append(UUID id, long offset, byte[] data) { service.append(id, owner, offset, data.length, hash(data), new ByteArrayInputStream(data)); }
    static String hash(byte[] data) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data)); }
        catch (Exception e) { throw new AssertionError(e); }
    }
    static void code(String code, org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(FileFailure.class, e -> assertThat(e.code).isEqualTo(code));
    }
    @Test void resumedChunksSurviveNewServiceAndCompletionIsIdempotent() throws Exception {
        var input = input(bytes);
        var upload = service.create(owner, input);
        assertThat(service.create(owner, input)).isEqualTo(upload);
        UUID id = upload.uploadId();
        append(id, 0, Arrays.copyOfRange(bytes, 0, 9));
        assertThat(service.list(owner, 20, 0)).isEmpty();
        service = new FilesService(db, tx, new FileStore(directory.toString(), db), 10_000_000, 20);
        assertThat(service.status(id, owner).receivedBytes()).isEqualTo(9);
        append(id, 9, Arrays.copyOfRange(bytes, 9, bytes.length));
        var file = service.complete(id, owner);
        assertThat(file.sha256()).isEqualTo(hash(bytes));
        assertThat(file.visibility()).isEqualTo("PUBLIC");
        assertThat(service.complete(id, owner)).isEqualTo(file);
        assertThat(Files.readAllBytes(store.path(id))).isEqualTo(bytes);
        assertThat(db.queryForObject("SELECT count(*) FROM file_audit WHERE action='file.completed'", Long.class)).isEqualTo(1);
        assertThat(service.list(owner, 20, 0)).containsExactly(file);
        service.visibility(id, owner, "PRIVATE");
        assertThat(service.create(owner, input).uploadId()).isEqualTo(id); // Later settings do not change the creation request identity.
    }
    @Test void checksumTruncationAndWrongOffsetsDoNotAdvanceConfirmedBytes() throws Exception {
        UUID id = create().uploadId();
        code("FILE_CHECKSUM_MISMATCH", () -> service.append(id, owner, 0, bytes.length, "0".repeat(64), new ByteArrayInputStream(bytes)));
        assertThat(Files.size(store.path(id))).isZero();
        code("INVALID_REQUEST", () -> service.append(id, owner, 0, bytes.length, hash(bytes), new ByteArrayInputStream(new byte[2])));
        assertThat(service.status(id, owner).receivedBytes()).isZero();
        code("FILE_OFFSET_CONFLICT", () -> append(id, 1, new byte[1]));
        append(id, 0, bytes);
        code("FILE_OFFSET_CONFLICT", () -> append(id, 0, bytes));
        assertThat(Files.readAllBytes(store.path(id))).isEqualTo(bytes);
    }
    @Test void leftoverBytesAfterDbRollbackAreTruncatedBeforeResume() throws Exception {
        UUID id = create().uploadId();
        append(id, 0, Arrays.copyOfRange(bytes, 0, 5));
        Files.write(store.path(id), "uncommitted-tail".getBytes(), StandardOpenOption.APPEND);
        append(id, 5, Arrays.copyOfRange(bytes, 5, bytes.length));
        assertThat(service.complete(id, owner).size()).isEqualTo(bytes.length);
        assertThat(Files.readAllBytes(store.path(id))).isEqualTo(bytes);
    }
    @Test void everyManagementAndSessionOperationIsEnvironmentOrOwnerScoped() {
        UUID id = create().uploadId();
        var otherEnvironment = new FileAccess.Context(owner.projectId(), UUID.randomUUID(), owner.credentialId());
        var otherProject = new FileAccess.Context(UUID.randomUUID(), owner.environmentId(), owner.credentialId());
        var otherKey = new FileAccess.Context(owner.projectId(), owner.environmentId(), UUID.randomUUID());
        for (var wrong : List.of(otherEnvironment, otherProject, otherKey)) {
            code("FILE_NOT_FOUND", () -> service.status(id, wrong));
            code("FILE_NOT_FOUND", () -> service.append(id, wrong, 0, bytes.length, hash(bytes), new ByteArrayInputStream(bytes)));
            code("FILE_NOT_FOUND", () -> service.complete(id, wrong));
            code("FILE_NOT_FOUND", () -> service.cancel(id, wrong));
        }
        append(id, 0, bytes);
        service.complete(id, owner);
        for (var wrong : List.of(otherEnvironment, otherProject)) {
            assertThat(service.list(wrong, 20, 0)).isEmpty();
            code("FILE_NOT_FOUND", () -> service.detail(id, wrong));
            code("FILE_NOT_FOUND", () -> service.visibility(id, wrong, "PRIVATE"));
            code("FILE_NOT_FOUND", () -> service.delete(id, wrong));
        }
        assertThat(service.detail(id, otherKey).fileId()).isEqualTo(id); // Server keys manage their environment, not individual members.
    }
    @Test void concurrentSameOffsetAndCompletionHaveOnePersistentResult() throws Exception {
        UUID id = create().uploadId();
        try (var pool = Executors.newFixedThreadPool(2)) {
            var gate = new CountDownLatch(1);
            var task = (Callable<String>) () -> { gate.await(); try { append(id, 0, bytes); return "ok"; } catch (FileFailure e) { return e.code; } };
            var a = pool.submit(task); var b = pool.submit(task); gate.countDown();
            assertThat(List.of(a.get(), b.get())).containsExactlyInAnyOrder("ok", "FILE_OFFSET_CONFLICT");
            var first = pool.submit(() -> service.complete(id, owner));
            var second = pool.submit(() -> service.complete(id, owner));
            assertThat(first.get().fileId()).isEqualTo(second.get().fileId());
        }
        assertThat(db.queryForObject("SELECT count(*) FROM file_audit WHERE action='file.completed'", Long.class)).isEqualTo(1);
    }
    @Test void expiredCancelledAndDeletedFilesAreDurablyPurgedWithoutDeletingReadyFiles() {
        UUID expired = create().uploadId(), cancelled = create().uploadId(), ready = create().uploadId();
        for (UUID id : List.of(expired, cancelled, ready)) append(id, 0, bytes);
        service.complete(ready, owner);
        db.update("UPDATE files SET upload_expires_at=now()-interval '1 second' WHERE id IN (?,?)", expired, ready);
        assertThat(service.status(expired, owner).state()).isEqualTo("EXPIRED");
        code("FILE_UPLOAD_EXPIRED", () -> service.complete(expired, owner));
        service.cancel(cancelled, owner); service.cancel(cancelled, owner);
        code("FILE_UPLOAD_CLOSED", () -> service.cancel(ready, owner));
        service.cleanup(); service.cleanup();
        assertThat(Files.exists(store.path(expired))).isFalse();
        assertThat(Files.exists(store.path(cancelled))).isFalse();
        assertThat(Files.exists(store.path(ready))).isTrue();
        service.visibility(ready, owner, "PRIVATE");
        assertThat(service.detail(ready, owner).visibility()).isEqualTo("PRIVATE");
        service.delete(ready, owner); service.delete(ready, owner);
        code("FILE_NOT_FOUND", () -> service.downloadable(ready));
        service.cleanup();
        assertThat(Files.exists(store.path(ready))).isFalse();
        assertThat(db.queryForObject("SELECT count(*) FROM file_audit WHERE action='file.deleted'", Long.class)).isEqualTo(1);
    }
    @Test void limitsUnknownPolicyPathNamesAndIdempotencyConflictsAreRejected() {
        var original = input(bytes);
        service.create(owner, original);
        code("FILE_REQUEST_CONFLICT", () -> service.create(owner, new FilesService.Create(original.requestId(), "different", original.size(), original.sha256(), null, null)));
        for (String name : List.of("../secret", "..", "a\\secret", "a\r\nb"))
            code("INVALID_REQUEST", () -> service.create(owner, new FilesService.Create(UUID.randomUUID(), name, 1L, hash(bytes), null, null)));
        code("FILE_TOO_LARGE", () -> service.create(owner, new FilesService.Create(UUID.randomUUID(), "big", FilesService.MAX_FILE + 1, hash(bytes), null, null)));
        code("FILE_RETENTION_UNKNOWN", () -> service.create(owner, new FilesService.Create(UUID.randomUUID(), "x", 0L, hash(new byte[0]), null, "week")));
        var small = new FilesService(db, tx, store, bytes.length, 20);
        code("FILE_QUOTA_EXCEEDED", () -> small.create(owner, input(bytes)));
        var one = new FilesService(db, tx, store, 10_000_000, 1);
        code("FILE_QUOTA_EXCEEDED", () -> one.create(owner, input(new byte[0])));
        assertThat(db.queryForList("SELECT code FROM file_retention_policies", String.class)).containsExactlyInAnyOrder("default", "tmp", "영구");
    }
    @Test void incompleteAndWrongWholeFileNeverBecomePublicAndEmptyFilesWork() {
        var input = new FilesService.Create(UUID.randomUUID(), "wrong", (long) bytes.length, "0".repeat(64), null, null);
        UUID id = service.create(owner, input).uploadId();
        code("FILE_UPLOAD_INCOMPLETE", () -> service.complete(id, owner));
        append(id, 0, bytes);
        code("FILE_CHECKSUM_MISMATCH", () -> service.complete(id, owner));
        code("FILE_NOT_FOUND", () -> service.downloadable(id));
        UUID empty = service.create(owner, input(new byte[0])).uploadId();
        assertThat(service.complete(empty, owner).sha256()).isEqualTo(hash(new byte[0]));
    }
    @Test void failedAuditRollsBackCompletionAndDeletion() {
        UUID id = create().uploadId(); append(id, 0, bytes);
        db.execute("ALTER TABLE file_audit ADD CONSTRAINT reject_mutation CHECK (action='upload.created')");
        assertThatThrownBy(() -> service.complete(id, owner)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(service.status(id, owner).state()).isEqualTo("UPLOADING");
        db.execute("ALTER TABLE file_audit DROP CONSTRAINT reject_mutation");
        service.complete(id, owner);
        db.execute("ALTER TABLE file_audit ADD CONSTRAINT reject_mutation CHECK (action<>'file.deleted')");
        assertThatThrownBy(() -> service.delete(id, owner)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(service.downloadable(id).state()).isEqualTo("READY");
        assertThat(Files.exists(store.path(id))).isTrue();
    }
    @Test void adminAndCredentialSubjectsCannotShareSessionsEvenWithTheSameUuid() {
        var administrator=new FileAccess.Context(owner.projectId(),owner.environmentId(),owner.credentialId(),"ADMIN");
        var input=input(bytes);
        UUID keySession=service.create(owner,input).uploadId();
        UUID adminSession=service.create(administrator,input).uploadId();
        assertThat(keySession).isNotEqualTo(adminSession);
        code("FILE_NOT_FOUND",()->service.status(keySession,administrator));
        code("FILE_NOT_FOUND",()->service.status(adminSession,owner));
        assertThat(service.resumable(administrator)).extracting(x -> x.upload().uploadId()).containsExactly(adminSession);
        assertThat(service.resumable(administrator)).extracting(FilesService.Resumable::requestId).containsExactly(input.requestId());
        var another=new FileAccess.Context(owner.projectId(),owner.environmentId(),UUID.randomUUID(),"ADMIN");
        assertThat(service.resumable(another)).isEmpty();
        code("FILE_NOT_FOUND",()->service.cancel(adminSession,another));
        assertThat(db.queryForObject("SELECT actor FROM file_audit WHERE file_id=?",String.class,adminSession)).startsWith("admin:");
    }
    @Test void ticketsAreSingleUseExpireAndAreRevokedByVisibilityOrDeletion() {
        var tickets=new DownloadTickets(db,tx,service);
        UUID id=create().uploadId(); append(id,0,bytes); service.complete(id,owner);
        var ticket=tickets.create(id,owner,java.time.Instant.now().plusSeconds(300));
        String token=ticket.downloadUrl().substring(ticket.downloadUrl().lastIndexOf('/')+1);
        assertThat(tickets.consume(token)).isEqualTo(id);
        code("FILE_NOT_FOUND",()->tickets.consume(token));
        var hidden=tickets.create(id,owner,java.time.Instant.now().plusSeconds(300));
        service.visibility(id,owner,"PRIVATE");
        code("FILE_NOT_FOUND",()->tickets.consume(hidden.downloadUrl().substring(hidden.downloadUrl().lastIndexOf('/')+1)));
        var expired=tickets.create(id,owner,java.time.Instant.now().plusSeconds(300));
        db.update("UPDATE file_download_tickets SET expires_at=now()-interval '1 second'");
        code("FILE_NOT_FOUND",()->tickets.consume(expired.downloadUrl().substring(expired.downloadUrl().lastIndexOf('/')+1)));
        var deleted=tickets.create(id,owner,java.time.Instant.now().plusSeconds(300)); service.delete(id,owner);
        code("FILE_NOT_FOUND",()->tickets.consume(deleted.downloadUrl().substring(deleted.downloadUrl().lastIndexOf('/')+1)));
    }
}
