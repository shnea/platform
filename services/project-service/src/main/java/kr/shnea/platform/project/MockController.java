package kr.shnea.platform.project;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

@RestController
@ConditionalOnProperty(name="platform.mode", havingValue="dev")
class MockController {
    record Login(@NotNull @Pattern(regexp="kakao|naver|google") String provider,
                 @NotNull @Pattern(regexp="[a-zA-Z0-9_-]{1,80}") String subject) {}
    private final ProjectService service;
    MockController(ProjectService service) { this.service = service; }

    @PostMapping("/api/v1/dev/login")
    Object login(@RequestHeader(value="X-Platform-Key", required=false) String key,
                 @Valid @RequestBody Login request) {
        return service.mockLogin(key, request.provider(), request.subject());
    }
}
