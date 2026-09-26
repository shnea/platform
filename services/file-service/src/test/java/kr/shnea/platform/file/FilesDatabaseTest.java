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
        store = new FileStore(directory.toString());
        service = new FilesService(db, tx, store, 10_000_000, 20);
    }
    @AfterEach void cleanup() { if (admin != null) admin.execute("DROP SCHEMA " + schema + " CASCADE"); }
    RetentionService retention() {return new RetentionService(db,tx,org.mockito.Mockito.mock(FileAccess.class));}
    FileVideos videos(){return new FileVideos(db,tx,store,service,org.mockito.Mockito.mock(FileAccess.class),10_000_000);}
    FileViews views(){return new FileViews(db,tx,service,org.mockito.Mockito.mock(FileAccess.class),store,videos());}
    FileShares shares(){return new FileShares(db,tx,service,org.mockito.Mockito.mock(FileAccess.class),views());}
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
        assertThat(landscape).extracting(FileVideos.Variant::quality).containsExactly(360,720,1080);
        var portrait=FileVideos.variants(new FileVideos.Source(720,1280,12,false));
        assertThat(portrait).extracting(FileVideos.Variant::quality).containsExactly(360,720);
        assertThat(portrait.getFirst().width()).isEqualTo(360);assertThat(portrait.getFirst().height()).isEqualTo(640);
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
        service = new FilesService(db, tx, new FileStore(directory.toString()), 10_000_000, 20);
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
