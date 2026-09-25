package kr.shnea.platform.project;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.UUID;
import java.util.LinkedHashMap;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

@RestController
@ConditionalOnProperty(name="platform.mode", havingValue="dev")
class MockController {
    record Login(@NotNull @Pattern(regexp="kakao|naver|google") String provider,
                 @NotNull @Pattern(regexp="[a-zA-Z0-9_-]{1,80}") String subject,
                 @Pattern(regexp="success|cancelled|access_denied|provider_unavailable") String scenario) {}
    private final ProjectService service;
    MockController(ProjectService service) { this.service = service; }

    @PostMapping("/api/v1/dev/login")
    Object login(@RequestHeader(value="X-Platform-Key", required=false) String key,
                 @Valid @RequestBody Login request) {
        var result = service.mockLogin(key, request.provider(), request.subject(), request.scenario());
        return ResponseEntity.status(result.httpStatus()).body(result.result());
    }

    @PostMapping("/api/v1/admin/environments/{id}/mock-login")
    Object preview(@PathVariable UUID id, @Valid @RequestBody Login request, @AuthenticationPrincipal Jwt user) {
        var result = service.previewMockLogin(id, request.provider(), request.subject(), request.scenario(), user.getSubject());
        // The console reports the outcome without exposing an end-user credential to the browser.
        var safe = new LinkedHashMap<String, Object>();
        for (String field : List.of("mode", "scenario", "provider", "projectId", "environmentId", "userId", "issuer", "expiresIn", "error"))
            if (result.result().containsKey(field)) safe.put(field, result.result().get(field));
        return new ProjectService.MockResult(result.httpStatus(), safe);
    }
}
