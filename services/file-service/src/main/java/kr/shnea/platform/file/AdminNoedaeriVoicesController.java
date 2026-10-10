package kr.shnea.platform.file;

import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

@RestController
class AdminNoedaeriVoicesController {
    private final FileAccess access;
    private final NoedaeriVoices voices;
    private final DownloadTickets tickets;
    AdminNoedaeriVoicesController(FileAccess access,NoedaeriVoices voices,DownloadTickets tickets){this.access=access;this.voices=voices;this.tickets=tickets;}
    @GetMapping("/api/v1/files/admin/environments/{environmentId}/noedaeri/voices") Object list(@PathVariable UUID environmentId,@AuthenticationPrincipal Jwt user){return noStore(voices.list(access.administrator(environmentId,user.getSubject())));}
    @GetMapping("/api/v1/files/admin/environments/{environmentId}/noedaeri/voices/{id}") Object detail(@PathVariable UUID environmentId,@PathVariable UUID id,@AuthenticationPrincipal Jwt user){return noStore(voices.detail(access.administrator(environmentId,user.getSubject()),id));}
    @PatchMapping("/api/v1/files/admin/environments/{environmentId}/noedaeri/voices/{id}") Object rename(@PathVariable UUID environmentId,@PathVariable UUID id,@AuthenticationPrincipal Jwt user,@RequestBody JsonNode body){return noStore(voices.rename(access.administrator(environmentId,user.getSubject()),id,body));}
    @DeleteMapping("/api/v1/files/admin/environments/{environmentId}/noedaeri/voices/{id}") Object delete(@PathVariable UUID environmentId,@PathVariable UUID id,@AuthenticationPrincipal Jwt user,@RequestHeader(value="X-Confirm-Voice",required=false) String confirmation){return noStore(voices.delete(access.administrator(environmentId,user.getSubject()),id,confirmation));}
    @PostMapping("/api/v1/files/admin/environments/{environmentId}/noedaeri/voices/{id}/sample-ticket") Object sample(@PathVariable UUID environmentId,@PathVariable UUID id,@AuthenticationPrincipal Jwt user){var context=access.administrator(environmentId,user.getSubject());return noStore(tickets.create(voices.sample(context,id),context,user.getExpiresAt()));}
    private static ResponseEntity<?> noStore(Object body){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);}
}
