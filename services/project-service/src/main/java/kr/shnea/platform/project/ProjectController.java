package kr.shnea.platform.project;

import java.util.List;
import java.util.UUID;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
class ProjectController {
    record NewProject(@Pattern(regexp="[a-z][a-z0-9-]{1,39}") @NotNull String code,
                      @NotBlank @Size(max=120) String name) {}
    record NewEnvironment(@Pattern(regexp="[a-z][a-z0-9-]{1,39}") @NotNull String code,
                          @NotNull String kind, @NotNull Boolean registrationAllowed,
                          @NotNull List<@NotBlank String> redirectUris) {}
    record ProjectSettings(@NotBlank @Size(max=120) String name, @NotNull String status, @NotNull Long revision) {}
    record EnvironmentSettings(@NotNull Boolean registrationAllowed, @NotNull List<@NotBlank String> redirectUris, @NotNull Long revision) {}
    @PutMapping("/api/v1/admin/projects/{id}")
    Object updateProject(@PathVariable UUID id, @Valid @RequestBody ProjectSettings request, @AuthenticationPrincipal Jwt user) {
        return service.updateProject(id, request.name(), request.status(), request.revision(), user.getSubject());
    }
    @PutMapping("/api/v1/admin/environments/{id}")
    Object updateEnvironment(@PathVariable UUID id, @Valid @RequestBody EnvironmentSettings request, @AuthenticationPrincipal Jwt user) {
        return service.updateEnvironment(id, request.registrationAllowed(), request.redirectUris(), request.revision(), user.getSubject());
    }
    @GetMapping("/api/v1/admin/environments/{id}/credentials")
    Object credentials(@PathVariable UUID id) { return service.credentials(id); }
    private final ProjectService service;
    ProjectController(ProjectService service) { this.service = service; }

    @GetMapping("/api/v1/admin/projects")
    Object projects(@RequestParam(defaultValue="50") int limit, @RequestParam(defaultValue="0") int offset) {
        return service.projects(limit, offset);
    }
    @PostMapping("/api/v1/admin/projects") @ResponseStatus(HttpStatus.CREATED)
    Object create(@Valid @RequestBody NewProject request, @AuthenticationPrincipal Jwt user) {
        return service.createProject(request.code(), request.name(), user.getSubject());
    }
    @GetMapping("/api/v1/admin/projects/{id}/environments")
    Object environments(@PathVariable UUID id) { return service.environments(id); }
    @PostMapping("/api/v1/admin/projects/{id}/environments") @ResponseStatus(HttpStatus.CREATED)
    Object environment(@PathVariable UUID id, @Valid @RequestBody NewEnvironment request, @AuthenticationPrincipal Jwt user) {
        return service.createEnvironment(id, request.code(), request.kind(), request.registrationAllowed(), request.redirectUris(), user.getSubject());
    }
    @PostMapping("/api/v1/admin/environments/{id}/provision")
    Object provision(@PathVariable UUID id, @AuthenticationPrincipal Jwt user) { return service.provision(id, user.getSubject()); }
    @PostMapping("/api/v1/admin/environments/{id}/credentials") @ResponseStatus(HttpStatus.CREATED)
    Object issue(@PathVariable UUID id, @AuthenticationPrincipal Jwt user) { return service.issueCredential(id, user.getSubject()); }
    @DeleteMapping("/api/v1/admin/credentials/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    void revoke(@PathVariable UUID id, @AuthenticationPrincipal Jwt user) { service.revokeCredential(id, user.getSubject()); }
    @GetMapping("/api/v1/integration/context")
    Object context(@RequestHeader(value="X-Platform-Key", required=false) String key) { return service.context(key); }
    @GetMapping("/api/v1/admin/audit-events")
    Object audit(@RequestParam(defaultValue="50") int limit) { return service.auditEvents(limit); }
}
