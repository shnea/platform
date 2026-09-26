package kr.shnea.platform.file;

import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
class RetentionController {
    private final FileAccess access; private final RetentionService retention; private final FilesService files;
    RetentionController(FileAccess access,RetentionService retention,FilesService files){this.access=access;this.retention=retention;this.files=files;}
    @GetMapping("/api/v1/files/retention-policies")
    Object policies(@RequestHeader(value="X-Platform-Key",required=false)String key){return retention.overview(access.require(key,"files:read")).policies();}
    @GetMapping("/api/v1/files/admin/environments/{environmentId}/retention")
    Object overview(@PathVariable UUID environmentId,@AuthenticationPrincipal Jwt user){return retention.overview(access.administrator(environmentId,user.getSubject()));}
    @PostMapping("/api/v1/files/admin/environments/{environmentId}/retention/preview")
    Object preview(@PathVariable UUID environmentId,@AuthenticationPrincipal Jwt user,@RequestBody RetentionService.Policy input){return retention.preview(access.administrator(environmentId,user.getSubject()),input);}
    @PutMapping("/api/v1/files/admin/environments/{environmentId}/retention/policies")
    Object save(@PathVariable UUID environmentId,@AuthenticationPrincipal Jwt user,@RequestBody RetentionService.SavePolicy input){return retention.savePolicy(access.administrator(environmentId,user.getSubject()),input);}
    @PutMapping("/api/v1/files/admin/environments/{environmentId}/retention/settings")
    Object settings(@PathVariable UUID environmentId,@AuthenticationPrincipal Jwt user,@RequestBody RetentionService.SaveSettings input){return retention.saveSettings(access.administrator(environmentId,user.getSubject()),input);}
    @GetMapping("/api/v1/files/admin/environments/{environmentId}/retention/candidates")
    Object candidates(@PathVariable UUID environmentId,@AuthenticationPrincipal Jwt user,@RequestParam(defaultValue="0")int offset){return retention.candidates(access.administrator(environmentId,user.getSubject()),offset);}
    @GetMapping("/api/v1/files/admin/environments/{environmentId}/retention/history")
    Object history(@PathVariable UUID environmentId,@AuthenticationPrincipal Jwt user){return retention.history(access.administrator(environmentId,user.getSubject()));}
    record Change(String retentionCode,String expectedRetentionCode){}
    @PutMapping("/api/v1/files/admin/environments/{environmentId}/{id}/retention")
    Object change(@PathVariable UUID environmentId,@PathVariable UUID id,@AuthenticationPrincipal Jwt user,@RequestBody Change input){return files.retention(id,access.administrator(environmentId,user.getSubject()),input.retentionCode(),input.expectedRetentionCode());}
    @PutMapping("/api/v1/files/{id}/retention")
    Object changeServer(@PathVariable UUID id,@RequestHeader(value="X-Platform-Key",required=false)String key,@RequestBody Change input){return files.retention(id,access.require(key,"files:write"),input.retentionCode(),input.expectedRetentionCode());}
}
