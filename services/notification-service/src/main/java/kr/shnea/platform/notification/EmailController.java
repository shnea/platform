package kr.shnea.platform.notification;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

@RestController
@RequestMapping("/internal/v1/email")
class EmailController {
    record Message(@NotNull UUID id, @NotNull UUID environmentId, @NotBlank @Pattern(regexp="p-[a-f0-9]{32}") String realm,
                   @NotBlank @Email @Size(max=320) String recipient, @NotBlank @Size(max=998) String subject,
                   @NotNull @Size(max=100000) String textBody, @NotNull @Size(max=200000) String htmlBody) {}
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final NcpMail ncp;
    private final String mode;
    private final RestClient projects;
    private final JsonMapper json = new JsonMapper();

    EmailController(JdbcTemplate db, TransactionTemplate tx, NcpMail ncp,
            @Value("${PLATFORM_MODE:prod}") String mode, @Value("${PLATFORM_MAIL_SECRET}") String secret,
            @Value("${PROJECT_INTERNAL_URL:http://project-service:8080}") String url) {
        if (!List.of("dev", "prod").contains(mode)) throw new IllegalArgumentException("Invalid platform mode");
        this.db = db; this.tx = tx; this.ncp = ncp; this.mode = mode;
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
        factory.setReadTimeout(Duration.ofSeconds(5));
        projects = RestClient.builder().baseUrl(url).requestFactory(factory).defaultHeader("X-Platform-Mail-Key", secret)
            .requestInterceptor(kr.shnea.platform.http.RequestTrace.propagate()).build();
    }
    @GetMapping("/readiness") Map<String, Object> readiness() {
        return Map.of("mode", mode, "ready", mode.equals("dev") || ncp.ready());
    }
    @PostMapping Object send(@Valid @RequestBody Message message) throws Exception {
        if (message.recipient().chars().anyMatch(Character::isISOControl) || message.subject().chars().anyMatch(Character::isISOControl)
                || (message.textBody().isBlank() && message.htmlBody().isBlank()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        Map<?, ?> context = projects.get().uri("/internal/v1/email/environments/{id}", message.environmentId()).retrieve().body(Map.class);
        if (context == null || !message.realm().equals(context.get("realm")) || !mode.equals(context.get("mode"))
                || !Boolean.TRUE.equals(context.get("active"))) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        boolean mock = mode.equals("dev") && "DEV".equals(context.get("kind"));
        if (!mock && !(mode.equals("prod") && "PROD".equals(context.get("kind")) && ncp.ready()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        String fingerprint = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
            .digest(json.writeValueAsString(message).getBytes(StandardCharsets.UTF_8)));
        // A PostgreSQL transaction lock bounds concurrent rate checks across instances.
        String state = tx.execute(status -> {
            db.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", Object.class, message.environmentId().toString());
            var existing = db.queryForList("SELECT fingerprint,state FROM identity_email WHERE id=?", message.id());
            if (!existing.isEmpty()) {
                if (!fingerprint.equals(existing.getFirst().get("fingerprint"))) throw new ResponseStatusException(HttpStatus.CONFLICT);
                return (String) existing.getFirst().get("state");
            }
            int recent = db.queryForObject("SELECT count(*) FROM identity_email WHERE environment_id=? AND created_at > now()-interval '1 minute'", Integer.class, message.environmentId());
            if (recent >= 20) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS);
            db.update("INSERT INTO identity_email(id,environment_id,fingerprint,delivery,state,recipient,subject,text_body) VALUES (?,?,?,?,?,?,?,?)",
                message.id(), message.environmentId(), fingerprint, mock ? "MOCK" : "NCP", mock ? "MOCK" : "SENDING",
                mock ? message.recipient() : null, mock ? message.subject() : null, mock ? message.textBody() : null);
            return "NEW";
        });
        if (state.equals("NEW")) {
            if (mock) state = "MOCK";
            else {
                var outcome = ncp.send(message);
                db.update("UPDATE identity_email SET state=?,provider_id=? WHERE id=?", outcome.state(), outcome.providerId(), message.id());
                state = outcome.state();
            }
        }
        if (!List.of("MOCK", "ACCEPTED").contains(state)) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Email delivery not confirmed; no automatic retry");
        return Map.of("id", message.id(), "state", state);
    }
    @GetMapping("/inbox/{environmentId}") List<Map<String, Object>> inbox(@PathVariable UUID environmentId) {
        if (!mode.equals("dev")) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return db.queryForList("SELECT id,recipient,subject,text_body AS \"textBody\",created_at AS \"createdAt\" FROM identity_email WHERE environment_id=? AND delivery='MOCK' AND created_at > now()-interval '1 hour' ORDER BY created_at DESC,id LIMIT 100", environmentId);
    }
    @Scheduled(fixedDelay=60000) void expire() {
        db.update("UPDATE identity_email SET recipient=NULL,subject=NULL,text_body=NULL WHERE delivery='MOCK' AND created_at < now()-interval '1 hour' AND text_body IS NOT NULL");
        db.update("UPDATE identity_email SET state='UNKNOWN' WHERE state='SENDING' AND created_at < now()-interval '2 minutes'");
        db.update("DELETE FROM identity_email WHERE created_at < now()-interval '30 days'");
    }
}
