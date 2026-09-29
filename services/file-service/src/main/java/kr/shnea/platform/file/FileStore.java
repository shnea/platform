package kr.shnea.platform.file;

import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** A private Docker volume; neither client filenames nor public URLs are filesystem paths. */
@Component
class FileStore {
    static final long MAX_CHUNK = 8 * 1024 * 1024;
    static final long DISK_RESERVE = 256 * 1024 * 1024;
    private final Path root;
    private final JdbcTemplate db;
    private static final DateTimeFormatter STORAGE_DATE = DateTimeFormatter.ofPattern("yyyy/MM/dd", Locale.ROOT)
        .withZone(ZoneId.of("Asia/Seoul"));
    final Object mediaMonitor = new Object();
    FileStore(@Value("${platform.files.directory:/app/storage}") String directory, JdbcTemplate db) throws IOException {
        root = Path.of(directory).toAbsolutePath().normalize();
        this.db = db;
        Files.createDirectories(root);
    }
    static String storagePath(UUID id, UUID project, UUID environment, String name, Instant created) {
        int dot = name.lastIndexOf('.');
        String extension = dot > 0 ? name.substring(dot + 1).toLowerCase(Locale.ROOT) : "bin";
        if (!extension.matches("[a-z0-9]{1,16}")) extension = "bin";
        String type = switch (extension) {
            case "jpg", "jpeg", "jfif", "png", "gif", "webp", "avif", "heic", "heif", "bmp", "tif", "tiff", "svg", "ico" -> "images";
            case "mp4", "webm", "mov", "mkv", "avi", "m4v", "mpeg", "mpg", "ts", "mts", "m2ts", "wmv", "flv", "3gp", "ogv" -> "videos";
            case "mp3", "wav", "ogg", "oga", "m4a", "aac", "flac", "opus", "aiff", "wma" -> "audio";
            case "pdf", "txt", "md", "csv", "json", "xml", "html", "rtf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "hwp", "hwpx", "odt", "ods", "odp" -> "documents";
            case "zip", "7z", "rar", "tar", "gz", "bz2", "xz", "tgz" -> "archives";
            default -> "others";
        };
        return type + "/" + STORAGE_DATE.format(created) + "/" + project + "/" + environment + "/" + id + "/original." + extension;
    }
    Path path(UUID id) {
        String key = db.queryForObject("SELECT storage_path FROM files WHERE id=?", String.class, id);
        if (key == null) return root.resolve(id + ".bin");
        Path relative = Path.of(key);
        Path target = root.resolve(relative).normalize();
        if (relative.isAbsolute() || key.contains("\\") || !target.startsWith(root) || target.equals(root)
                || !target.getParent().getFileName().toString().equals(id.toString()))
            throw new IllegalStateException("Invalid file storage path");
        return target;
    }
    Path thumbnail(UUID id) { return derivative(path(id), id, "thumbnail.jpg", ".jpg"); }
    Path preview(UUID id) { return derivative(path(id), id, "preview.webp", ".preview.webp"); }
    Path previewTemporary(UUID id) { return derivative(path(id), id, "preview.webp.tmp", ".preview.webp.tmp"); }
    Path video(UUID id) { return derivative(path(id), id, "hls", ".hls"); }
    private Path derivative(Path original, UUID id, String name, String legacySuffix) {
        return original.getParent().equals(root) ? root.resolve(id + legacySuffix) : original.resolveSibling(name);
    }
    void deleteVideo(UUID id) {
        deleteVideo(video(id));
    }
    private void deleteVideo(Path target) {
        try {
            if(Files.exists(target))try(var paths=Files.walk(target)) {
                for(Path path:paths.sorted(java.util.Comparator.reverseOrder()).toList())Files.deleteIfExists(path);
            }
        }catch(IOException e){throw FileFailure.unavailable();}
    }
    long usableSpace() {
        try { return Math.max(0, Files.getFileStore(root).getUsableSpace() - DISK_RESERVE); }
        catch (IOException e) { throw FileFailure.unavailable(); }
    }
    void append(UUID id, long offset, long length, String checksum, InputStream input) {
        if (length < 1 || length > MAX_CHUNK) throw new FileFailure("FILE_CHUNK_TOO_LARGE", 413, "조각 크기는 1바이트 이상 8MiB 이하여야 합니다.");
        if (usableSpace() < length) throw new FileFailure("FILE_STORAGE_FULL", 507, "파일 저장 공간이 부족합니다.");
        Path target = path(id);
        try { Files.createDirectories(target.getParent()); }
        catch (IOException e) { throw FileFailure.unavailable(); }
        try (var file = new RandomAccessFile(target.toFile(), "rw")) {
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
        Path target = path(id);
        try {
            if (size == 0 && !Files.exists(target)) {
                Files.createDirectories(target.getParent());
                try (var empty = new RandomAccessFile(target.toFile(), "rw")) { empty.getFD().sync(); }
            }
            // An uncommitted trailing chunk must never become part of a completed file.
            try (var file = new RandomAccessFile(target.toFile(), "rw")) {
                if (file.length() < size) throw FileFailure.unavailable();
                file.setLength(size);
            }
            var hash = digest();
            try (var input = new DigestInputStream(Files.newInputStream(target), hash)) {
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
        Path original = path(id);
        try {
            Files.deleteIfExists(original);
            Files.deleteIfExists(derivative(original, id, "thumbnail.jpg", ".jpg"));
            Files.deleteIfExists(derivative(original, id, "preview.webp", ".preview.webp"));
            Files.deleteIfExists(derivative(original, id, "preview.webp.tmp", ".preview.webp.tmp"));
            deleteVideo(derivative(original, id, "hls", ".hls"));
            // Remove only this file's empty directory, never a shared date/project directory.
            if (!original.getParent().equals(root)) Files.deleteIfExists(original.getParent());
        }
        catch (IOException e) { throw FileFailure.unavailable(); }
    }
    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
