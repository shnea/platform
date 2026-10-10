package kr.shnea.platform.file;

import java.io.IOException;
import java.util.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.json.JsonMapper;

@RestController
class AdminNoedaeriController {
    private final FileAccess access;
    private final FilesService files;
    private final NoedaeriTasks tasks;
    private final DownloadTickets tickets;
    AdminNoedaeriController(FileAccess access,FilesService files,NoedaeriTasks tasks,DownloadTickets tickets) {
        this.access=access;this.files=files;this.tasks=tasks;this.tickets=tickets;
    }
    @GetMapping("/api/v1/files/admin/environments/{environmentId}/noedaeri/services")
    Object services(@PathVariable UUID environmentId,@AuthenticationPrincipal Jwt user) {
        access.administrator(environmentId,user.getSubject());return noStore(tasks.services());
    }
    @PostMapping("/api/v1/files/admin/environments/{environmentId}/noedaeri/uploads")
    Object source(@PathVariable UUID environmentId,@AuthenticationPrincipal Jwt user,@RequestBody FilesService.Create input) {
        if(!"PRIVATE".equals(input.visibility())||!"tmp".equals(input.retentionCode()))throw FileFailure.invalid();
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore()).body(files.createSource(access.administrator(environmentId,user.getSubject()),input));
    }
    @PostMapping("/api/v1/files/admin/environments/{environmentId}/noedaeri/tasks")
    Object create(@PathVariable UUID environmentId,@AuthenticationPrincipal Jwt user,HttpServletRequest request) throws Exception {
        var context=access.administrator(environmentId,user.getSubject());byte[] bytes=request.getInputStream().readNBytes(64*1024+1);
        if(bytes.length>64*1024)throw new FileFailure("FILE_MEDIA_EVENT_TOO_LARGE",413,"뇌대리 테스트 입력은 64KiB 이하여야 합니다.");
        tools.jackson.databind.JsonNode body;
        try{body=new JsonMapper().readTree(bytes);}catch(RuntimeException error){throw FileFailure.invalid();}
        return ResponseEntity.status(202).cacheControl(CacheControl.noStore()).body(tasks.create(context,body));
    }
    @GetMapping("/api/v1/files/admin/environments/{environmentId}/noedaeri/tasks")
    Object list(@PathVariable UUID environmentId,@AuthenticationPrincipal Jwt user) {return noStore(tasks.list(access.administrator(environmentId,user.getSubject())));}
    @GetMapping("/api/v1/files/admin/environments/{environmentId}/noedaeri/tasks/{id}")
    Object detail(@PathVariable UUID environmentId,@PathVariable UUID id,@AuthenticationPrincipal Jwt user) {return noStore(tasks.detail(access.administrator(environmentId,user.getSubject()),id,true));}
    @PostMapping("/api/v1/files/admin/environments/{environmentId}/noedaeri/tasks/{id}/cancel")
    Object cancel(@PathVariable UUID environmentId,@PathVariable UUID id,@AuthenticationPrincipal Jwt user) {return noStore(tasks.cancel(access.administrator(environmentId,user.getSubject()),id));}
    @PostMapping("/api/v1/files/admin/environments/{environmentId}/noedaeri/tasks/{id}/recover")
    Object recover(@PathVariable UUID environmentId,@PathVariable UUID id,@AuthenticationPrincipal Jwt user) {return noStore(tasks.recover(access.administrator(environmentId,user.getSubject()),id));}
    @PostMapping("/api/v1/files/admin/environments/{environmentId}/noedaeri/tasks/{id}/artifacts/{name}/ticket")
    Object ticket(@PathVariable UUID environmentId,@PathVariable UUID id,@PathVariable String name,@AuthenticationPrincipal Jwt user) {
        var context=access.administrator(environmentId,user.getSubject());return noStore(tickets.create(tasks.artifact(context,id,name),context,user.getExpiresAt()));
    }
    private static ResponseEntity<?> noStore(Object body){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);}
}
