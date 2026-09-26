package kr.shnea.platform.file;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import jakarta.servlet.http.*;
import org.springframework.http.ContentDisposition;
import org.springframework.web.bind.annotation.*;

@RestController
class FilesController {
    private final FileAccess access;
    private final FilesService files;
    private final FileStore store;
    FilesController(FileAccess access, FilesService files, FileStore store) { this.access = access; this.files = files; this.store = store; }
    @PostMapping("/api/v1/files/uploads")
    @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    Object create(@RequestHeader(value="X-Platform-Key", required=false) String key, @RequestBody FilesService.Create body) {
        return files.create(access.require(key, "files:write"), body);
    }
    @GetMapping("/api/v1/files/uploads/{id}")
    Object status(@PathVariable UUID id, @RequestHeader(value="X-Platform-Key", required=false) String key) {
        return files.status(id, access.require(key, "files:write"));
    }
    @PatchMapping(value="/api/v1/files/uploads/{id}", consumes="application/octet-stream")
    Object append(@PathVariable UUID id, @RequestHeader(value="X-Platform-Key", required=false) String key,
                  @RequestHeader("Upload-Offset") long offset, @RequestHeader("X-Chunk-SHA256") String hash,
                  HttpServletRequest request) throws IOException {
        var context = access.require(key, "files:write");
        if (request.getContentLengthLong() < 0) throw new FileFailure("FILE_LENGTH_REQUIRED", 411, "조각의 Content-Length를 지정해 주세요.");
        return files.append(id, context, offset, request.getContentLengthLong(), hash, request.getInputStream());
    }
    @PostMapping("/api/v1/files/uploads/{id}/complete")
    Object complete(@PathVariable UUID id, @RequestHeader(value="X-Platform-Key", required=false) String key) {
        return files.complete(id, access.require(key, "files:write"));
    }
    @DeleteMapping("/api/v1/files/uploads/{id}") @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    void cancel(@PathVariable UUID id, @RequestHeader(value="X-Platform-Key", required=false) String key) {
        files.cancel(id, access.require(key, "files:write"));
    }
    @GetMapping("/api/v1/files")
    Object list(@RequestHeader(value="X-Platform-Key", required=false) String key,
                @RequestParam(defaultValue="20") int limit, @RequestParam(defaultValue="0") int offset) {
        return files.list(access.require(key, "files:read"), limit, offset);
    }
    @GetMapping("/api/v1/files/{id}")
    Object detail(@PathVariable UUID id, @RequestHeader(value="X-Platform-Key", required=false) String key) {
        return files.detail(id, access.require(key, "files:read"));
    }
    record Visibility(String visibility) {}
    @PutMapping("/api/v1/files/{id}/visibility")
    Object visibility(@PathVariable UUID id, @RequestHeader(value="X-Platform-Key", required=false) String key,
                      @RequestBody Visibility body) {
        return files.visibility(id, access.require(key, "files:write"), body.visibility());
    }
    @DeleteMapping("/api/v1/files/{id}") @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    void delete(@PathVariable UUID id, @RequestHeader(value="X-Platform-Key", required=false) String key) {
        files.delete(id, access.require(key, "files:delete"));
    }
    @GetMapping("/api/v1/files/{id}/download")
    void download(@PathVariable UUID id, @RequestHeader(value="X-Platform-Key", required=false) String key,
                  HttpServletRequest request, HttpServletResponse response) throws IOException {
        var row = files.downloadable(id);
        if (row.visibility().equals("PRIVATE")) {
            if (key == null) throw FileFailure.missing(); // Do not reveal protected file existence to anonymous callers.
            files.sameEnvironment(row, access.require(key, "files:read"));
        } else access.requireActive(row.environment());
        // Recheck after the remote authorization call, so a concurrent hide/delete cannot bypass it.
        var current = files.downloadable(id);
        if (current.visibility().equals("PRIVATE") && !row.visibility().equals("PRIVATE")) {
            if (key == null) throw FileFailure.missing();
            files.sameEnvironment(current, access.require(key, "files:read"));
        }
        try (var input = store.open(id)) {
            response.setContentType("application/octet-stream");
            response.setHeader("Content-Disposition", ContentDisposition.attachment().filename(row.name(), StandardCharsets.UTF_8).build().toString());
            response.setContentLengthLong(row.size());
            if (request.getMethod().equals("HEAD")) return;
            input.transferTo(response.getOutputStream());
            response.flushBuffer();
            files.used(id);
        }
    }
}
