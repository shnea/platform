package kr.shnea.platform.project;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

/** File service asks the credential owner; it never reads the project database directly. */
@RestController
class FileAccessController {
    private final ProjectService projects;
    private final byte[] secret;
    FileAccessController(ProjectService projects, @Value("${PLATFORM_FILES_SECRET:}") String secret) {
        this.projects = projects;
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }
    private void authorize(String supplied) {
        if (secret.length < 32 || supplied == null || !MessageDigest.isEqual(secret, supplied.getBytes(StandardCharsets.UTF_8)))
            throw ApiCode.ACCESS_DENIED.failure();
    }
    @PostMapping("/internal/v1/files/access")
    Object access(@RequestHeader(value="X-Platform-Files-Key", required=false) String secret,
                  @RequestHeader(value="X-Platform-Key", required=false) String key,
                  @RequestParam String scope) {
        authorize(secret);
        if (!Set.of("files:read", "files:write", "files:delete").contains(scope)) throw ApiCode.INSUFFICIENT_SCOPE.failure();
        var context = projects.context(key, scope);
        return Map.of("projectId", context.projectId(), "environmentId", context.environmentId(),
            "credentialId", UUID.fromString(key.split("_", 3)[1]));
    }
    @GetMapping("/internal/v1/files/environments/{id}")
    Object active(@PathVariable UUID id, @RequestHeader(value="X-Platform-Files-Key", required=false) String secret) {
        authorize(secret);
        return projects.fileEnvironment(id);
    }
}
