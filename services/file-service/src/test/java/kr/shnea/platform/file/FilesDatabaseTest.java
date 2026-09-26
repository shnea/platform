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
