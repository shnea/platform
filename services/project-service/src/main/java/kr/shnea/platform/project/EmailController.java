package kr.shnea.platform.project;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

@RestController
class EmailController {
    private final ProjectService service;
    private final EmailClient emails;
    private final String mode;
    private final byte[] secret;
    EmailController(ProjectService service, EmailClient emails, @Value("${platform.mode}") String mode,
                    @Value("${PLATFORM_MAIL_SECRET}") String secret) {
        if (secret.length() < 32) throw new IllegalArgumentException("Configure PLATFORM_MAIL_SECRET (32+ characters)");
        this.service = service; this.emails = emails; this.mode = mode; this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }
    @GetMapping("/internal/v1/email/environments/{id}")
    Object context(@PathVariable UUID id, @RequestHeader(value="X-Platform-Mail-Key", required=false) String provided) {
        if (provided == null || !MessageDigest.isEqual(secret, provided.getBytes(StandardCharsets.UTF_8)))
            throw ApiCode.ACCESS_DENIED.failure();
        return service.emailContext(id);
    }
    @GetMapping("/api/v1/admin/environments/{id}/email-inbox")
    Object inbox(@PathVariable UUID id, jakarta.servlet.http.HttpServletResponse response) {
        if (!mode.equals("dev")) throw ApiCode.RESOURCE_NOT_FOUND.failure();
        var context = service.emailContext(id);
        if (!"DEV".equals(context.get("kind"))) throw ApiCode.RESOURCE_NOT_FOUND.failure();
        response.setHeader("Cache-Control", "no-store");
        return emails.inbox(id);
    }
}
