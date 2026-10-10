package kr.shnea.platform.file;

import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@EnabledIfEnvironmentVariable(named="JOB_TEST_DB_URL",matches="jdbc:postgresql://[^/]+/job_checks")
@Timeout(30)
class NoedaeriTasksDatabaseTest {
    @TempDir Path directory;
    JdbcTemplate admin,db;TransactionTemplate tx;FileStore store;FilesService files;String schema;
    final JsonMapper json=new JsonMapper();
    final FileAccess.Context owner=new FileAccess.Context(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),"ADMIN");
    @BeforeEach void setup() throws Exception {
        String url=System.getenv("JOB_TEST_DB_URL");schema="noedaeri_test_"+UUID.randomUUID().toString().replace("-","");
        admin=new JdbcTemplate(new DriverManagerDataSource(url,"job_checks","isolated-test-only"));admin.execute("CREATE SCHEMA "+schema);
        var source=new DriverManagerDataSource(url+"?currentSchema="+schema,"job_checks","isolated-test-only");
        Flyway.configure().dataSource(source).defaultSchema(schema).locations("classpath:db/migration").load().migrate();
        db=new JdbcTemplate(source);tx=new TransactionTemplate(new DataSourceTransactionManager(source));store=new FileStore(directory.toString(),db);files=new FilesService(db,tx,store,100_000_000,20);
    }
    @AfterEach void cleanup(){if(admin!=null)admin.execute("DROP SCHEMA "+schema+" CASCADE");}
    UUID source(String name) throws Exception {
        byte[] bytes="test input".getBytes();String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        var upload=files.createSource(owner,new FilesService.Create(UUID.randomUUID(),name,(long)bytes.length,hash,"PRIVATE","tmp"));
        files.append(upload.uploadId(),owner,0,bytes.length,hash,new ByteArrayInputStream(bytes));return files.complete(upload.uploadId(),owner).fileId();
    }
    @Test void sourceOnlyUploadsNeverTriggerPackagesAndCannotChangeModeOnRetry() throws Exception {
        UUID id=source("test.mp4");
        var client=new NoedaeriClient("https://example.invalid","key","secret",100000);
        var access=mock(FileAccess.class);var videos=new FileVideos(db,tx,store,files,access,100_000_000,new MediaBackend("noedaeri"));
        var media=new NoedaeriMedia(db,tx,store,files,videos,access,client,new MediaBackend("noedaeri"));media.discover();media.claim();
        assertThat(db.queryForObject("SELECT count(*) FROM file_media_jobs",Integer.class)).isZero();
        assertThat(videos.status(id).state()).isEqualTo("UNSUPPORTED");
        var view=new FileViews(db,tx,files,access,store,videos,new MediaBackend("noedaeri"));assertThat(view.view(id).state()).isEqualTo("UNSUPPORTED");
        var row=db.queryForMap("SELECT * FROM files WHERE id=?",id);
        assertThatThrownBy(()->files.create(owner,new FilesService.Create((UUID)row.get("request_id"),"test.mp4",(Long)row.get("size_bytes"),(String)row.get("expected_sha256"),"PRIVATE","tmp"))).isInstanceOf(FileFailure.class);
    }
    @Test void presetRegistrationPersistsOneProfileAndNeverCreatesMediaJob() throws Exception {
        var client=mock(NoedaeriClient.class);when(client.configured()).thenReturn(true);UUID voice=UUID.randomUUID();
        var profile=voiceProfile(voice,"preset",null);when(client.registerVoice(anyMap())).thenReturn(profile);
        var tasks=new NoedaeriTasks(db,tx,files,store,mock(FileAccess.class),client);
        var body=json.readTree(json.writeValueAsString(Map.of("requestId",UUID.randomUUID(),"kind","tts.voice.register","input",Map.of("kind","preset","name","한국어 안내","speaker","Sohee"))));
        UUID id=(UUID)((Map<?,?>)tasks.create(owner,body)).get("id");tasks.advance(id);tasks.work();
        assertThat(tasks.detail(owner,id,true)).containsEntry("status","succeeded").containsEntry("voiceId",voice);
        assertThat(((Map<?,?>)tasks.create(owner,body)).get("reused")).isEqualTo(true);
        verify(client,times(1)).registerVoice(argThat(value->value.get("requester_id").equals(NoedaeriTaskContract.requester(owner))&&value.get("project").equals(owner.projectId().toString())&&value.get("idempotency_key").equals(id.toString())));
        verify(client,never()).create(any(),any(),anyMap(),anyMap());assertThat(db.queryForObject("SELECT payload FROM file_noedaeri_tasks WHERE id=?",String.class,id)).isNull();
        assertThatThrownBy(()->tasks.recover(owner,id)).isInstanceOf(FileFailure.class);
    }
    @Test void cloneRegistrationSavesValidatedReferenceBeforeReceiptAndDeletionRequiresConfirmation() throws Exception {
        UUID source=source("reference.mp3"),voice=UUID.randomUUID(),remote=UUID.randomUUID(),event=UUID.randomUUID();
        var client=mock(NoedaeriClient.class);when(client.configured()).thenReturn(true);var profile=voiceProfile(voice,"clone",remote);
        when(client.registerVoice(anyMap())).thenReturn(profile);when(client.voice(eq(voice),anyString(),any(),any())).thenReturn(profile);
        when(client.status(remote)).thenReturn(json.readTree(json.writeValueAsString(Map.of("id",remote,"kind","tts.voice.register","status","uploading"))),json.readTree(json.writeValueAsString(Map.of("id",remote,"kind","tts.voice.register","status","succeeded","terminal_event_id",event,"result",Map.of("type","voice_profile","voice_id",voice)))));
        byte[] bytes=referenceWave();doAnswer(call->{Files.write(call.getArgument(4),bytes);return null;}).when(client).voiceSample(eq(voice),anyString(),any(),any(),any());
        doAnswer(call->{assertThat(db.queryForObject("SELECT state FROM file_noedaeri_tasks WHERE job_id=?",String.class,remote)).isEqualTo("IMPORTED");assertThat(db.queryForObject("SELECT count(*) FROM files WHERE state='READY'",Integer.class)).isEqualTo(2);return null;}).when(client).receipt(remote,event);
        var tasks=new NoedaeriTasks(db,tx,files,store,mock(FileAccess.class),client);
        var body=json.readTree(json.writeValueAsString(Map.of("requestId",UUID.randomUUID(),"sourceFileId",source,"kind","tts.voice.register","input",Map.of("kind","clone","name","한국어 안내","reference_text","실제로 말한 대본"))));
        UUID id;
        try(var probe=mockStatic(FileViews.class)) {
            probe.when(()->FileViews.run(anyList(),eq(10))).thenReturn("{\"streams\":[{\"codec_type\":\"audio\",\"duration\":\"4\"}]}".getBytes());
            id=(UUID)((Map<?,?>)tasks.create(owner,body)).get("id");
        }
        tasks.advance(id);tasks.advance(id);
        UUID stored=tasks.artifact(owner,id,"reference.wav");assertThat(Files.readAllBytes(store.path(stored))).isEqualTo(bytes);verify(client,times(1)).upload(remote,store.path(source));verify(client,times(1)).registerVoice(anyMap());
        assertThat(db.queryForObject("SELECT manifest::text FROM file_noedaeri_tasks WHERE id=?",String.class,id)).doesNotContain("reference_text");
        var voices=new NoedaeriVoices(client,files,store,db);UUID preview=voices.sample(owner,voice);assertThat(voices.sample(owner,voice)).isEqualTo(preview);assertThat(Files.readAllBytes(store.path(preview))).isEqualTo(bytes);verify(client,times(2)).voiceSample(eq(voice),anyString(),any(),any(),any());
        assertThatThrownBy(()->voices.delete(owner,voice,"wrong")).isInstanceOf(FileFailure.class);verify(client,never()).deleteVoice(any(),anyString(),any(),any());
        when(client.deleteVoice(eq(voice),anyString(),any(),any())).thenReturn(json.readTree("{\"deleted\":false,\"status\":\"cleanup_failed\"}"));assertThat(voices.delete(owner,voice,voice.toString())).isEqualTo(Map.of("deleted",false,"status","cleanup_failed"));assertThat(files.detail(preview,owner)).isNotNull();
        when(client.deleteVoice(eq(voice),anyString(),any(),any())).thenReturn(json.readTree("{\"deleted\":true}"));assertThat(voices.delete(owner,voice,voice.toString())).isEqualTo(Map.of("deleted",true));assertThatThrownBy(()->files.downloadable(stored)).isInstanceOf(FileFailure.class);assertThatThrownBy(()->files.downloadable(preview)).isInstanceOf(FileFailure.class);
    }
    @Test void storedReferenceDurationIsCheckedBeforeCreatingOrDispatchingARegistration() throws Exception {
        UUID source=source("reference.mp3");var client=mock(NoedaeriClient.class);when(client.configured()).thenReturn(true);
        var tasks=new NoedaeriTasks(db,tx,files,store,mock(FileAccess.class),client);
        try(var probe=mockStatic(FileViews.class)) {
            for(String seconds:List.of("2.99","3","30.01")) {
                probe.when(()->FileViews.run(anyList(),eq(10))).thenReturn(("{\"streams\":[{\"codec_type\":\"audio\",\"duration\":\""+seconds+"\"}]}").getBytes());
                var body=json.readTree(json.writeValueAsString(Map.of("requestId",UUID.randomUUID(),"sourceFileId",source,"kind","tts.voice.register","input",Map.of("kind","clone","name","참조 검사","reference_text","실제 대본"))));
                assertThatThrownBy(()->tasks.create(owner,body)).isInstanceOf(FileFailure.class);
            }
            assertThat(db.queryForObject("SELECT count(*) FROM file_noedaeri_tasks",Integer.class)).isZero();
            probe.when(()->FileViews.run(anyList(),eq(10))).thenReturn("{\"streams\":[{\"codec_type\":\"audio\",\"duration\":\"3.001\"}]}".getBytes());
            var body=json.readTree(json.writeValueAsString(Map.of("requestId",UUID.randomUUID(),"sourceFileId",source,"kind","tts.voice.register","input",Map.of("kind","clone","name","참조 검사","reference_text","실제 대본"))));
            assertThat(((Map<?,?>)tasks.create(owner,body)).get("reused")).isEqualTo(false);
            assertThat(((Map<?,?>)tasks.create(owner,body)).get("reused")).isEqualTo(true);
            probe.verify(()->FileViews.run(anyList(),eq(10)),times(4));
        }
        verify(client,never()).registerVoice(anyMap());verify(client,never()).create(any(),any(),anyMap(),anyMap());
    }

    @Test void voiceScopeForgeryAndReferenceDurationAreRejected() throws Exception {
        var client=mock(NoedaeriClient.class);when(client.configured()).thenReturn(true);var profile=(tools.jackson.databind.node.ObjectNode)voiceProfile(UUID.randomUUID(),"preset",null);profile.put("requester_id","other");
        when(client.voices(anyString(),any(),any())).thenReturn(json.createArrayNode().add(profile));var voices=new NoedaeriVoices(client,files,store,db);
        assertThatThrownBy(()->voices.list(owner)).isInstanceOf(FileFailure.class);
        byte[] invalid=referenceWave();java.nio.ByteBuffer.wrap(invalid).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(40,2);
        assertThatThrownBy(()->NoedaeriTaskContract.validate("tts.voice.register","reference.wav",invalid)).isInstanceOf(IOException.class);
    }
    @Test void uncertainTtsCancellationOnlyRecoversExistingJobWithoutCreatingAnother() throws Exception {
        var client=mock(NoedaeriClient.class);when(client.configured()).thenReturn(true);when(client.create(any(),eq("tts.synthesize"),anyMap(),anyMap())).thenThrow(new IOException("response lost"));
        var tasks=new NoedaeriTasks(db,tx,files,store,mock(FileAccess.class),client);
        var body=json.readTree(json.writeValueAsString(Map.of("requestId",UUID.randomUUID(),"kind","tts.synthesize","input",Map.of("text","안녕하세요"))));
        UUID id=(UUID)((Map<?,?>)tasks.create(owner,body)).get("id");assertThatThrownBy(()->tasks.advance(id)).isInstanceOf(IOException.class);tasks.cancel(owner,id);
        assertThatThrownBy(()->tasks.advance(id)).isInstanceOf(IOException.class);verify(client,times(1)).create(any(),eq("tts.synthesize"),anyMap(),anyMap());
        UUID remote=UUID.randomUUID();when(client.findJob(id)).thenReturn(remote);when(client.status(remote)).thenReturn(json.readTree(json.writeValueAsString(Map.of("id",remote,"kind","tts.synthesize","status","running"))));
        tasks.advance(id);verify(client).cancel(remote);assertThat(tasks.detail(owner,id,false)).containsEntry("cancelRequested",true).containsEntry("status","running");
    }
    tools.jackson.databind.JsonNode voiceProfile(UUID id,String kind,UUID job) {
        var result=json.createObjectNode();result.put("id",id.toString());result.put("kind",kind);result.put("name","한국어 안내");result.put("requester_id",NoedaeriTaskContract.requester(owner));result.put("project",owner.projectId().toString());result.put("environment",owner.environmentId().toString());result.put("status","ready");result.put("sample_available",kind.equals("clone"));result.put("reference_text","실제로 말한 대본");if(job!=null)result.put("registration_job_id",job.toString());return result;
    }
    byte[] referenceWave() {
        var wav=java.nio.ByteBuffer.allocate(144044).order(java.nio.ByteOrder.LITTLE_ENDIAN);wav.put("RIFF".getBytes()).putInt(144036).put("WAVEfmt ".getBytes()).putInt(16).putShort((short)1).putShort((short)1).putInt(24000).putInt(48000).putShort((short)2).putShort((short)16).put("data".getBytes()).putInt(144000);return wav.array();
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"stt.transcribe","video.subtitles","pdf.extract","ocr.recognize","tts.synthesize","video.thumbnail"})
    void signedCompletionImportsEveryFileBeforeReceiptAndReusesRequestsWithoutExecution(String kind) throws Exception {
        boolean tts=kind.equals("tts.synthesize");
        UUID source=tts?null:source(kind.equals("ocr.recognize")?"source.png":kind.equals("pdf.extract")?"source.pdf":kind.equals("video.thumbnail")?"source.mp4":"audio.wav"),remote=UUID.randomUUID(),event=UUID.randomUUID();int[] calls=new int[3];
        var server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        var artifacts=new LinkedHashMap<String,byte[]>();
        for(String name:NoedaeriTaskContract.files(kind)) {
            byte[] bytes=new byte[0];
            if(name.endsWith(".json"))bytes=(kind.equals("pdf.extract")?"{\"text\":\"\",\"page_count\":1,\"pages\":[{\"page\":1,\"method\":\"text\",\"text\":\"\",\"lines\":[]}]}":kind.equals("ocr.recognize")?"{\"text\":\"\",\"coordinate_system\":\"normalized_top_left\",\"lines\":[]}":"{\"text\":\"\",\"duration_seconds\":1,\"segments\":[]}").getBytes();
            if(name.endsWith(".zip")){var zip=new ByteArrayOutputStream();try(var writer=new java.util.zip.ZipOutputStream(zip)){writer.putNextEntry(new java.util.zip.ZipEntry("text.txt"));writer.closeEntry();}bytes=zip.toByteArray();}
            if(name.endsWith(".vtt"))bytes="WEBVTT\n\n".getBytes();
            if(name.endsWith(".jpg"))bytes=new byte[]{(byte)255,(byte)216,(byte)255,0};
            if(name.endsWith(".wav")){var wav=java.nio.ByteBuffer.allocate(46).order(java.nio.ByteOrder.LITTLE_ENDIAN);wav.put("RIFF".getBytes()).putInt(38).put("WAVEfmt ".getBytes()).putInt(16).putShort((short)1).putShort((short)1).putInt(24000).putInt(48000).putShort((short)2).putShort((short)16).put("data".getBytes()).putInt(2).putShort((short)0);bytes=wav.array();}
            artifacts.put(name,bytes);
        }
        var manifest=new LinkedHashMap<String,Object>();manifest.put("type",switch(kind){case "stt.transcribe"->"stt_transcribe";case "video.subtitles"->"video_subtitles";case "pdf.extract"->"pdf_extract";case "ocr.recognize"->"ocr_recognize";default->"artifact";});
        for(String name:List.copyOf(artifacts.keySet()))if(name.endsWith(".zip")) {
            var zip=new ByteArrayOutputStream();try(var writer=new java.util.zip.ZipOutputStream(zip)){for(var entry:artifacts.entrySet())if(!entry.getKey().endsWith(".zip")){writer.putNextEntry(new java.util.zip.ZipEntry(entry.getKey()));writer.write(entry.getValue());writer.closeEntry();}}artifacts.put(name,zip.toByteArray());
        }
        if(tts||kind.equals("video.thumbnail")){manifest.put("name",artifacts.keySet().iterator().next());manifest.put("media_type",tts?"audio/wav":"image/jpeg");}else manifest.put("files",artifacts.keySet());
        server.createContext("/api/v1/jobs",exchange->{
            try {
                assertThat(exchange.getRequestHeaders().getFirst("X-Noedaeri-API-Key")).isEqualTo("key");String path=exchange.getRequestURI().getPath();byte[] response;
                if(path.equals("/api/v1/jobs")){calls[0]++;var body=json.readTree(exchange.getRequestBody().readAllBytes());assertThat(body.path("kind").asString()).isEqualTo(kind);assertThat(body.path("input").path("type").asString()).isEqualTo(tts?"text":"upload");response=json.writeValueAsBytes(Map.of("id",remote));}
                else if(path.endsWith("/input")){calls[1]++;exchange.getRequestBody().readAllBytes();response="{}".getBytes();}
                else if(path.endsWith("/receipt")) {
                    assertThat(db.queryForObject("SELECT state FROM file_noedaeri_tasks WHERE job_id=?",String.class,remote)).isEqualTo("IMPORTED");
                    var output=json.readTree(db.queryForObject("SELECT artifacts::text FROM file_noedaeri_tasks WHERE job_id=?",String.class,remote));
                    for(String name:artifacts.keySet())assertThat(Files.readAllBytes(store.path(UUID.fromString(output.path(name).asString())))).isEqualTo(artifacts.get(name));
                    calls[2]++;response="{\"accepted\":true}".getBytes();
                }else if(path.endsWith("/result"))response=artifacts.entrySet().stream().filter(entry->entry.getKey().endsWith(".zip")||entry.getKey().endsWith(".wav")||entry.getKey().endsWith(".jpg")).findFirst().orElseThrow().getValue();
                else if(path.contains("/files/"))response=artifacts.get(path.substring(path.lastIndexOf('/')+1));
                else response=json.writeValueAsBytes(Map.of("id",remote,"kind",kind,"status",!tts&&calls[1]==0?"uploading":"succeeded","terminal_event_id",event,"expires_at",java.time.Instant.now().plusSeconds(3600).toString(),"result",manifest));
                exchange.sendResponseHeaders(200,response.length==0?-1:response.length);exchange.getResponseBody().write(response);
            }catch(Exception error){throw new RuntimeException(error);}finally{exchange.close();}
        });server.start();
        try {
            var client=new NoedaeriClient(java.net.URI.create("http://127.0.0.1:"+server.getAddress().getPort()),"key","secret",100000);
            var tasks=new NoedaeriTasks(db,tx,files,store,mock(FileAccess.class),client);UUID request=UUID.randomUUID();
            var body=json.readTree(json.writeValueAsString(tts?Map.of("requestId",request,"kind",kind,"input",Map.of("text","안녕하세요")):Map.of("requestId",request,"sourceFileId",source,"kind",kind)));
            var created=(Map<?,?>)tasks.create(owner,body);UUID id=(UUID)created.get("id");assertThat(created.get("reused")).isEqualTo(false);
            assertThat(((Map<?,?>)tasks.create(owner,body)).get("reused")).isEqualTo(true);
            var changed=json.readTree(json.writeValueAsString(tts?Map.of("requestId",request,"kind",kind,"input",Map.of("text","다른 입력")):Map.of("requestId",request,"sourceFileId",source,"kind","tts.synthesize","input",Map.of("text","다른 입력"))));
            assertThatThrownBy(()->tasks.create(owner,changed)).isInstanceOf(FileFailure.class);
            tasks.advance(id);
            if(!tts) {
                var webhook=new NoedaeriWebhook(db,tx,client);var incoming=new org.springframework.mock.web.MockHttpServletRequest();long now=java.time.Instant.now().getEpochSecond();
                byte[] payload=json.writeValueAsBytes(Map.of("version",1,"event_id",event,"job_id",remote,"idempotency_key",id,"type","job.succeeded","job",Map.of("kind",kind,"status","succeeded")));
                var mac=javax.crypto.Mac.getInstance("HmacSHA256");mac.init(new javax.crypto.spec.SecretKeySpec("secret".getBytes(),"HmacSHA256"));mac.update((now+".").getBytes());
                incoming.setContent(payload);incoming.addHeader("X-Noedaeri-Event-ID",event.toString());incoming.addHeader("X-Noedaeri-Timestamp",String.valueOf(now));incoming.addHeader("X-Noedaeri-Signature","sha256="+HexFormat.of().formatHex(mac.doFinal(payload)));
                assertThat(webhook.receive(incoming)).containsEntry("accepted",true);
                tasks.advance(id);
            }
            assertThat(tasks.detail(owner,id,true)).containsEntry("status","succeeded").containsEntry("resultAvailable",true);
            assertThat(calls).containsExactly(1,tts?0:1,1);tasks.detail(owner,id,true);tasks.create(owner,body);assertThat(calls).containsExactly(1,tts?0:1,1);
            assertThatThrownBy(()->tasks.detail(new FileAccess.Context(owner.projectId(),UUID.randomUUID(),owner.credentialId(),"ADMIN"),id,true)).isInstanceOf(FileFailure.class);
            if(source!=null){UUID stored=tasks.artifact(owner,id,artifacts.keySet().iterator().next());files.used(stored);assertThat(db.queryForObject("SELECT last_used_at IS NOT NULL FROM files WHERE id=?",Boolean.class,source)).isTrue();files.delete(source,owner);assertThatThrownBy(()->tasks.artifact(owner,id,artifacts.keySet().iterator().next())).isInstanceOf(FileFailure.class);assertThatThrownBy(()->files.downloadable(stored)).isInstanceOf(FileFailure.class);tasks.maintainResults();assertThat(tasks.detail(owner,id,false)).containsEntry("status","cancelled").containsEntry("resultAvailable",false);}
        }finally{server.stop(0);}
    }
}
