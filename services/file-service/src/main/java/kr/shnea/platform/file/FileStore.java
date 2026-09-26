package kr.shnea.platform.file;

import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** A private Docker volume; neither client filenames nor public URLs are filesystem paths. */
@Component
class FileStore {
    static final long MAX_CHUNK = 8 * 1024 * 1024;
    static final long DISK_RESERVE = 256 * 1024 * 1024;
    private final Path root;
    FileStore(@Value("${platform.files.directory:/app/storage}") String directory) throws IOException {
        root = Path.of(directory).toAbsolutePath().normalize();
        Files.createDirectories(root);
    }
    Path path(UUID id) { return root.resolve(id + ".bin"); }
    long usableSpace() {
        try { return Math.max(0, Files.getFileStore(root).getUsableSpace() - DISK_RESERVE); }
        catch (IOException e) { throw FileFailure.unavailable(); }
    }
    void append(UUID id, long offset, long length, String checksum, InputStream input) {
        if (length < 1 || length > MAX_CHUNK) throw new FileFailure("FILE_CHUNK_TOO_LARGE", 413, "조각 크기는 1바이트 이상 8MiB 이하여야 합니다.");
        if (usableSpace() < length) throw new FileFailure("FILE_STORAGE_FULL", 507, "파일 저장 공간이 부족합니다.");
        try (var file = new RandomAccessFile(path(id).toFile(), "rw")) {
            // The DB offset is authoritative. Discard bytes left by a crash/rolled-back transaction.
            if (file.length() < offset) throw FileFailure.unavailable();
            file.setLength(offset);
            file.seek(offset);
            try {
                var digest = digest();
                byte[] buffer = new byte[64 * 1024];
                long remaining = length;
                while (remaining > 0) {
                    int count = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                    if (count < 0) throw FileFailure.invalid();
                    if (count == 0) continue;
                    file.write(buffer, 0, count);
                    digest.update(buffer, 0, count);
                    remaining -= count;
                }
                if (input.read() != -1) throw FileFailure.invalid();
                if (!HexFormat.of().formatHex(digest.digest()).equals(checksum))
                    throw new FileFailure("FILE_CHECKSUM_MISMATCH", 422, "전송한 조각의 해시가 일치하지 않습니다. 같은 위치부터 다시 보내 주세요.");
                file.getFD().sync(); // Flush before the caller commits its received_bytes update.
            } catch (IOException | RuntimeException e) {
                file.setLength(offset);
                throw e;
            }
        } catch (IOException e) { throw FileFailure.unavailable(); }
    }
    String hash(UUID id, long size) {
        try {
            if (size == 0 && !Files.exists(path(id))) {
                try (var empty = new RandomAccessFile(path(id).toFile(), "rw")) { empty.getFD().sync(); }
            }
            // An uncommitted trailing chunk must never become part of a completed file.
            try (var file = new RandomAccessFile(path(id).toFile(), "rw")) {
                if (file.length() < size) throw FileFailure.unavailable();
                file.setLength(size);
            }
            var hash = digest();
            try (var input = new DigestInputStream(Files.newInputStream(path(id)), hash)) {
                input.transferTo(OutputStream.nullOutputStream());
            }
            return HexFormat.of().formatHex(hash.digest());
        } catch (IOException e) { throw FileFailure.unavailable(); }
    }
    InputStream open(UUID id) {
        try { return Files.newInputStream(path(id)); }
        catch (IOException e) { throw FileFailure.unavailable(); }
    }
    void delete(UUID id) {
        try { Files.deleteIfExists(path(id)); }
        catch (IOException e) { throw FileFailure.unavailable(); }
    }
    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
