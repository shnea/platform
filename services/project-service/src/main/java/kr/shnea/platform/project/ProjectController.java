package kr.shnea.platform.project;

import java.util.List;
import java.util.UUID;
import java.time.Instant;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
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
    record NewCredential(@Future Instant expiresAt, @Size(min=1, max=2) List<@NotBlank String> scopes) {}
    record AuthenticationSettings(@NotNull Boolean loginWithEmail, @NotNull Boolean verifyEmail,
                                  @NotNull Boolean resetPasswordAllowed, @NotNull @Min(12) @Max(128) Integer passwordMinLength,
                                  @NotBlank @Size(max=64) String revision) {}
    @GetMapping("/api/v1/admin/environments/{id}/authentication-policy")
    Object authenticationPolicy(@PathVariable UUID id) { return service.authenticationPolicy(id); }
    @PutMapping("/api/v1/admin/environments/{id}/authentication-policy")
    Object updateAuthenticationPolicy(@PathVariable UUID id, @Valid @RequestBody AuthenticationSettings request,
                                       @AuthenticationPrincipal Jwt user) {
        return service.updateAuthenticationPolicy(id, request, user.getSubject());
    }
    record SocialSettings(@NotNull Boolean enabled, @NotBlank @Size(max=64) String revision) {}
    @GetMapping("/api/v1/admin/environments/{id}/social-providers")
    Object socialProviders(@PathVariable UUID id) { return service.socialProviders(id); }
    @PutMapping("/api/v1/admin/environments/{id}/social-providers/{provider}")
    Object updateSocialProvider(@PathVariable UUID id, @PathVariable String provider,
                                @Valid @RequestBody SocialSettings request, @AuthenticationPrincipal Jwt user) {
        return service.updateSocialProvider(id, provider, request, user.getSubject());
    }
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
    @GetMapping("/api/v1/admin/environments/{id}/credential-scopes")
    Object credentialScopes(@PathVariable UUID id) { return service.credentialScopes(id); }
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
    Object issue(@PathVariable UUID id, @Valid @RequestBody(required=false) NewCredential request, @AuthenticationPrincipal Jwt user) {
        return service.issueCredential(id, request == null ? null : request.expiresAt(),
            request == null ? null : request.scopes(), user.getSubject());
    }
    @DeleteMapping("/api/v1/admin/credentials/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    void revoke(@PathVariable UUID id, @AuthenticationPrincipal Jwt user) { service.revokeCredential(id, user.getSubject()); }
    @GetMapping("/api/v1/integration/context")
    Object context(@RequestHeader(value="X-Platform-Key", required=false) String key) { return service.context(key, "integration:read"); }
    @GetMapping("/api/v1/admin/audit-events")
    Object audit(@RequestParam(defaultValue="50") int limit) { return service.auditEvents(limit); }
}
