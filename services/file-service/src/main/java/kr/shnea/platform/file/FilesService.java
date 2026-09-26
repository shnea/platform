package kr.shnea.platform.file;

import java.io.InputStream;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
class FilesService {
    static final long MAX_FILE = 5_000_000_000L;
    record Create(UUID requestId, String originalName, Long size, String sha256, String visibility, String retentionCode) {}
    record Upload(UUID uploadId, String state, long size, long receivedBytes, long maxChunkBytes,
                  Instant expiresAt, UUID fileId) {}
    record FileInfo(UUID fileId, String originalName, long size, String sha256, String visibility,
                    String retentionCode, Instant createdAt, Instant lastUsedAt, String downloadUrl) {}
    record Row(UUID id, UUID project, UUID environment, UUID owner, String name, long size, String hash,
               long offset, String state, String visibility, String retention, Instant expires,
               Instant completed, Instant used) {}
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final FileStore store;
    private final long quota;
    private final int pendingLimit;
    FilesService(JdbcTemplate db, TransactionTemplate tx, FileStore store,
                 @Value("${platform.files.environment-quota:50000000000}") long quota,
                 @Value("${platform.files.pending-limit:20}") int pendingLimit) {
        if (quota < 1 || pendingLimit < 1) throw new IllegalArgumentException("File quota/limit must be positive");
        this.db = db; this.tx = new TransactionTemplate(tx.getTransactionManager());
        this.tx.setTimeout(300);
        this.store = store; this.quota = quota; this.pendingLimit = pendingLimit;
    }
    Upload create(FileAccess.Context context, Create input) {
        validate(input);
        String visibility = input.visibility() == null ? "PUBLIC" : input.visibility();
        String retention = input.retentionCode() == null ? "default" : input.retentionCode();
        return tx.execute(status -> {
            // Short creation lock accounts for pending reservations across this shared storage volume.
            db.execute("SELECT pg_advisory_xact_lock(736452918)");
            var previous = db.query("SELECT * FROM files WHERE environment_id=? AND owner_credential_id=? AND request_id=?",
                this::row, context.environmentId(), context.credentialId(), input.requestId());
            if (!previous.isEmpty()) {
                var old = previous.getFirst();
                if (!old.name().equals(input.originalName()) || old.size() != input.size() || !old.hash().equals(input.sha256())
                        || !db.queryForObject("SELECT upload_visibility FROM files WHERE id=?", String.class, old.id()).equals(visibility)
                        || !old.retention().equals(retention))
                    throw new FileFailure("FILE_REQUEST_CONFLICT", 409, "같은 요청 ID에 다른 파일 정보가 지정되었습니다.");
                return upload(old);
            }
            long reserved = db.queryForObject("SELECT coalesce(sum(size_bytes),0) FROM files WHERE environment_id=? AND purged_at IS NULL", Long.class, context.environmentId());
            long pending = db.queryForObject("SELECT count(*) FROM files WHERE environment_id=? AND state='UPLOADING'", Long.class, context.environmentId());
            if (input.size() > quota - reserved || pending >= pendingLimit)
                throw new FileFailure("FILE_QUOTA_EXCEEDED", 409, "환경의 파일 용량 또는 진행 중 업로드 수 제한을 초과했습니다.");
            long remaining = db.queryForObject("SELECT coalesce(sum(size_bytes-received_bytes),0) FROM files WHERE state='UPLOADING'", Long.class);
            if (input.size() > store.usableSpace() - remaining)
                throw new FileFailure("FILE_STORAGE_FULL", 507, "예약 가능한 파일 저장 공간이 부족합니다.");
            db.update("INSERT INTO file_retention_policies(environment_id,code,unused_days) VALUES (?,'default',365),(?,'tmp',1),(?,'영구',NULL) ON CONFLICT DO NOTHING",
                context.environmentId(), context.environmentId(), context.environmentId());
            if (db.queryForObject("SELECT count(*) FROM file_retention_policies WHERE environment_id=? AND code=?", Long.class, context.environmentId(), retention) != 1)
                throw new FileFailure("FILE_RETENTION_UNKNOWN", 400, "등록된 파일 보존 코드를 선택해 주세요.");
            UUID id = UUID.randomUUID();
            db.update("""
                INSERT INTO files(id,project_id,environment_id,owner_credential_id,request_id,original_name,size_bytes,
                    expected_sha256,visibility,upload_visibility,retention_code,upload_expires_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,now()+interval '24 hours')
                """, id, context.projectId(), context.environmentId(), context.credentialId(), input.requestId(),
                input.originalName(), input.size(), input.sha256(), visibility, visibility, retention);
            audit(id, context, "upload.created");
            return upload(get(id, false));
        });
    }
    Upload status(UUID id, FileAccess.Context context) { return upload(owned(id, context, false)); }
    Upload append(UUID id, FileAccess.Context context, long offset, long length, String hash, InputStream input) {
        if (offset < 0 || hash == null || !hash.matches("[a-f0-9]{64}")) throw FileFailure.invalid();
        return tx.execute(status -> {
            Row row = owned(id, context, true);
            writable(row);
            if (row.offset() != offset) throw new FileFailure("FILE_OFFSET_CONFLICT", 409, "수신 위치가 다릅니다. 세션 상태를 조회하고 해당 위치부터 다시 보내 주세요.");
            if (length < 1 || length > row.size() - offset) throw FileFailure.invalid();
            store.append(id, offset, length, hash, input);
            db.update("UPDATE files SET received_bytes=? WHERE id=?", offset + length, id);
            return upload(get(id, false));
        });
    }
    FileInfo complete(UUID id, FileAccess.Context context) {
        return tx.execute(status -> {
            Row row = owned(id, context, true);
            if (row.state().equals("READY")) return info(row);
            writable(row);
            if (row.offset() != row.size()) throw new FileFailure("FILE_UPLOAD_INCOMPLETE", 409, "아직 전송하지 않은 조각이 있습니다.");
            if (!store.hash(id, row.size()).equals(row.hash()))
                throw new FileFailure("FILE_CHECKSUM_MISMATCH", 422, "전체 파일의 해시가 일치하지 않습니다. 업로드를 취소하고 원본 파일로 다시 시작해 주세요.");
            db.update("UPDATE files SET state='READY',completed_at=now(),last_used_at=now() WHERE id=?", id);
            audit(id, context, "file.completed");
            return info(get(id, false));
        });
    }
    void cancel(UUID id, FileAccess.Context context) {
        tx.executeWithoutResult(status -> {
            Row row = owned(id, context, true);
            if (Set.of("CANCELLED", "EXPIRED").contains(row.state())) return;
            if (!row.state().equals("UPLOADING")) throw new FileFailure("FILE_UPLOAD_CLOSED", 409, "완료한 파일은 업로드 취소로 삭제되지 않습니다.");
            db.update("UPDATE files SET state='CANCELLED' WHERE id=?", id);
            audit(id, context, "upload.cancelled");
        });
    }
    List<FileInfo> list(FileAccess.Context context, int limit, int offset) {
        if (limit < 1 || limit > 100 || offset < 0) throw FileFailure.invalid();
        return db.query("SELECT * FROM files WHERE environment_id=? AND project_id=? AND state='READY' ORDER BY created_at DESC,id LIMIT ? OFFSET ?",
            this::row, context.environmentId(), context.projectId(), limit, offset).stream().map(this::info).toList();
    }
    FileInfo detail(UUID id, FileAccess.Context context) { return info(managed(id, context, false)); }
    FileInfo visibility(UUID id, FileAccess.Context context, String visibility) {
        if (!Set.of("PUBLIC", "PRIVATE").contains(visibility == null ? "" : visibility)) throw FileFailure.invalid();
        return tx.execute(status -> {
            Row row = managed(id, context, true);
            if (!row.visibility().equals(visibility)) {
                db.update("UPDATE files SET visibility=? WHERE id=?", visibility, id);
                audit(id, context, "file.visibility." + visibility.toLowerCase(Locale.ROOT));
            }
            return info(get(id, false));
        });
    }
    void delete(UUID id, FileAccess.Context context) {
        tx.executeWithoutResult(status -> {
            Row row = get(id, true);
            sameEnvironment(row, context);
            if (row.state().equals("DELETED")) return;
            if (!row.state().equals("READY")) throw FileFailure.missing();
            db.update("UPDATE files SET state='DELETED' WHERE id=?", id);
            audit(id, context, "file.deleted");
        });
    }
    Row downloadable(UUID id) {
        Row row = get(id, false);
        if (!row.state().equals("READY")) throw FileFailure.missing();
        return row;
    }
    void used(UUID id) { db.update("UPDATE files SET last_used_at=now() WHERE id=? AND state='READY'", id); }
    void sameEnvironment(Row row, FileAccess.Context context) {
        if (!row.environment().equals(context.environmentId()) || !row.project().equals(context.projectId())) throw FileFailure.missing();
    }
    @Scheduled(fixedDelayString="${platform.files.cleanup-ms:60000}", initialDelay=10000)
    void cleanup() {
        // Durable terminal states retry physical deletion after restarts; never expire READY files here.
        var ids = db.queryForList("SELECT id FROM files WHERE purged_at IS NULL AND (state IN ('CANCELLED','EXPIRED','DELETED') OR (state='UPLOADING' AND upload_expires_at<=now())) ORDER BY upload_expires_at LIMIT 100", UUID.class);
        for (UUID id : ids) {
            try {
                tx.executeWithoutResult(status -> {
                    Row row = get(id, true);
                    if (row.state().equals("UPLOADING") && !row.expires().isAfter(Instant.now())) {
                        db.update("UPDATE files SET state='EXPIRED' WHERE id=?", id);
                        audit(id, new FileAccess.Context(row.project(), row.environment(), row.owner()), "upload.expired");
                    } else if (!Set.of("CANCELLED", "EXPIRED", "DELETED").contains(row.state())) return;
                    store.delete(id);
                    db.update("UPDATE files SET purged_at=coalesce(purged_at,now()) WHERE id=?", id);
                });
            } catch (RuntimeException error) {
                org.slf4j.LoggerFactory.getLogger(FilesService.class).warn("file_cleanup_failed fileId={}", id);
            }
        }
    }
    private Row get(UUID id, boolean lock) {
        if (lock) db.execute("SET LOCAL lock_timeout='5s'");
        return db.query("SELECT * FROM files WHERE id=?" + (lock ? " FOR UPDATE" : ""), this::row, id)
            .stream().findFirst().orElseThrow(FileFailure::missing);
    }
    private Row owned(UUID id, FileAccess.Context context, boolean lock) {
        Row row = get(id, lock);
        sameEnvironment(row, context);
        if (!row.owner().equals(context.credentialId())) throw FileFailure.missing();
        return row;
    }
    private Row managed(UUID id, FileAccess.Context context, boolean lock) {
        Row row = get(id, lock);
        sameEnvironment(row, context);
        if (!row.state().equals("READY")) throw FileFailure.missing();
        return row;
    }
    private void writable(Row row) {
        if (!row.state().equals("UPLOADING")) throw new FileFailure("FILE_UPLOAD_CLOSED", 409, "이미 종료된 업로드 세션입니다.");
        if (!row.expires().isAfter(Instant.now())) throw new FileFailure("FILE_UPLOAD_EXPIRED", 410, "업로드 세션이 만료되었습니다. 새 업로드를 시작해 주세요.");
    }
    private Upload upload(Row row) {
        String state = row.state().equals("UPLOADING") && !row.expires().isAfter(Instant.now()) ? "EXPIRED" : row.state();
        return new Upload(row.id(), state, row.size(), row.offset(), FileStore.MAX_CHUNK, row.expires(), row.state().equals("READY") ? row.id() : null);
    }
    private FileInfo info(Row row) {
        return new FileInfo(row.id(), row.name(), row.size(), row.hash(), row.visibility(), row.retention(), row.completed(), row.used(),
            "/api/v1/files/" + row.id() + "/download");
    }
    private void audit(UUID id, FileAccess.Context context, String action) {
        db.update("INSERT INTO file_audit(file_id,environment_id,actor,action,request_id) VALUES (?,?,?,?,?)",
            id, context.environmentId(), "credential:" + context.credentialId(), action, MDC.get("requestId"));
    }
    private static void validate(Create input) {
        if (input == null || input.requestId() == null || input.originalName() == null || input.originalName().isBlank()
                || input.originalName().length() > 255 || input.originalName().codePoints().anyMatch(c -> Character.isISOControl(c) || c == '/' || c == '\\')
                || input.originalName().equals(".") || input.originalName().equals("..") || input.size() == null || input.size() < 0
                || input.sha256() == null || !input.sha256().matches("[a-f0-9]{64}")
                || (input.visibility() != null && !Set.of("PUBLIC", "PRIVATE").contains(input.visibility()))
                || (input.retentionCode() != null && (input.retentionCode().isBlank() || input.retentionCode().length() > 60))) throw FileFailure.invalid();
        if (input.size() > MAX_FILE) throw new FileFailure("FILE_TOO_LARGE", 413, "파일 하나의 최대 크기는 5GB(5,000,000,000바이트)입니다.");
    }
    private Row row(ResultSet rs, int index) throws SQLException {
        var completed = rs.getTimestamp("completed_at"); var used = rs.getTimestamp("last_used_at");
        return new Row(rs.getObject("id", UUID.class), rs.getObject("project_id", UUID.class), rs.getObject("environment_id", UUID.class),
            rs.getObject("owner_credential_id", UUID.class), rs.getString("original_name"), rs.getLong("size_bytes"), rs.getString("expected_sha256"),
            rs.getLong("received_bytes"), rs.getString("state"), rs.getString("visibility"), rs.getString("retention_code"),
            rs.getTimestamp("upload_expires_at").toInstant(), completed == null ? null : completed.toInstant(), used == null ? null : used.toInstant());
    }
}
