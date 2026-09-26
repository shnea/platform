package kr.shnea.platform.file;

import java.io.IOException;
import java.util.UUID;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
class AdminFilesController {
    private final FileAccess access;
    private final FilesService files;
    private final DownloadTickets tickets;
    AdminFilesController(FileAccess access, FilesService files, DownloadTickets tickets) { this.access=access; this.files=files; this.tickets=tickets; }
    @GetMapping("/api/v1/files/admin/environments/{environmentId}")
    Object list(@PathVariable UUID environmentId, @AuthenticationPrincipal Jwt user,
                @RequestParam(defaultValue="20") int limit, @RequestParam(defaultValue="0") int offset) {
        return files.list(access.administrator(environmentId,user.getSubject()),limit,offset);
    }
    @GetMapping("/api/v1/files/admin/environments/{environmentId}/uploads")
    Object uploads(@PathVariable UUID environmentId, @AuthenticationPrincipal Jwt user) {
        return files.resumable(access.administrator(environmentId,user.getSubject()));
    }
    @PostMapping("/api/v1/files/admin/environments/{environmentId}/uploads")
    @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    Object create(@PathVariable UUID environmentId, @AuthenticationPrincipal Jwt user, @RequestBody FilesService.Create input) {
        return files.create(access.administrator(environmentId,user.getSubject()),input);
    }
    @GetMapping("/api/v1/files/admin/environments/{environmentId}/uploads/{id}")
    Object status(@PathVariable UUID environmentId, @PathVariable UUID id, @AuthenticationPrincipal Jwt user) {
        return files.status(id,access.administrator(environmentId,user.getSubject()));
    }
    @PatchMapping(value="/api/v1/files/admin/environments/{environmentId}/uploads/{id}",consumes="application/octet-stream")
    Object append(@PathVariable UUID environmentId, @PathVariable UUID id, @AuthenticationPrincipal Jwt user,
                  @RequestHeader("Upload-Offset") long offset, @RequestHeader("X-Chunk-SHA256") String checksum, HttpServletRequest request) throws IOException {
        var context=access.administrator(environmentId,user.getSubject());
        if(request.getContentLengthLong()<0) throw new FileFailure("FILE_LENGTH_REQUIRED",411,"조각의 Content-Length를 지정해 주세요.");
        return files.append(id,context,offset,request.getContentLengthLong(),checksum,request.getInputStream());
    }
    @PostMapping("/api/v1/files/admin/environments/{environmentId}/uploads/{id}/complete")
    Object complete(@PathVariable UUID environmentId, @PathVariable UUID id, @AuthenticationPrincipal Jwt user) {
        return files.complete(id,access.administrator(environmentId,user.getSubject()));
    }
    @DeleteMapping("/api/v1/files/admin/environments/{environmentId}/uploads/{id}")
    @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    void cancel(@PathVariable UUID environmentId, @PathVariable UUID id, @AuthenticationPrincipal Jwt user) {
        files.cancel(id,access.administrator(environmentId,user.getSubject()));
    }
    @PutMapping("/api/v1/files/admin/environments/{environmentId}/{id}/visibility")
    Object visibility(@PathVariable UUID environmentId, @PathVariable UUID id, @AuthenticationPrincipal Jwt user,
                      @RequestBody FilesController.Visibility body) {
        return files.visibility(id,access.administrator(environmentId,user.getSubject()),body.visibility());
    }
    @DeleteMapping("/api/v1/files/admin/environments/{environmentId}/{id}")
    @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    void delete(@PathVariable UUID environmentId, @PathVariable UUID id, @AuthenticationPrincipal Jwt user) {
        files.delete(id,access.administrator(environmentId,user.getSubject()));
    }
    @PostMapping("/api/v1/files/admin/environments/{environmentId}/{id}/download-ticket")
    Object ticket(@PathVariable UUID environmentId, @PathVariable UUID id, @AuthenticationPrincipal Jwt user) {
        return tickets.create(id,access.administrator(environmentId,user.getSubject()),user.getExpiresAt());
    }
}
