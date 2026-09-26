package kr.shnea.platform.file;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
class DownloadTickets {
    record Ticket(String downloadUrl, Instant expiresAt) {}
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final FilesService files;
    private final SecureRandom random = new SecureRandom();
    DownloadTickets(JdbcTemplate db, TransactionTemplate tx, FilesService files) { this.db=db; this.tx=tx; this.files=files; }
    Ticket create(UUID id, FileAccess.Context context, Instant tokenExpiry) {
        if(tokenExpiry==null || !tokenExpiry.isAfter(Instant.now())) throw new FileFailure("AUTHENTICATION_REQUIRED",401,"다시 로그인해 주세요.");
        Instant expiry=Instant.now().plusSeconds(60);
        if(tokenExpiry.isBefore(expiry)) expiry=tokenExpiry;
        final Instant expires=expiry;
        return tx.execute(status -> {
            db.execute("SET LOCAL lock_timeout='5s'");
            db.queryForList("SELECT id FROM files WHERE id=? FOR UPDATE",id);
            files.detail(id,context);
            // Bound outstanding tickets per file. Starting a new download replaces older unused tickets.
            db.update("DELETE FROM file_download_tickets WHERE file_id=?",id);
            byte[] bytes=new byte[32]; random.nextBytes(bytes);
            String token=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            db.update("INSERT INTO file_download_tickets(token_hash,file_id,expires_at) VALUES (?,?,?)",hash(token),id,java.sql.Timestamp.from(expires));
            return new Ticket("/api/v1/files/downloads/"+token,expires);
        });
    }
    UUID consume(String token) {
        if(token==null || !token.matches("[A-Za-z0-9_-]{43}")) throw FileFailure.missing();
        return db.query("DELETE FROM file_download_tickets WHERE token_hash=? AND expires_at>now() RETURNING file_id",
            (rs,n) -> rs.getObject(1,UUID.class),hash(token)).stream().findFirst().orElseThrow(FileFailure::missing);
    }
    private static String hash(String token) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
