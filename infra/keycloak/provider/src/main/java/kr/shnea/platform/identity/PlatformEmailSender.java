package kr.shnea.platform.identity;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.keycloak.email.EmailException;
import org.keycloak.email.EmailSenderProvider;
import org.keycloak.models.KeycloakSession;
import org.keycloak.util.JsonSerialization;

final class PlatformEmailSender implements EmailSenderProvider {
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
        .followRedirects(HttpClient.Redirect.NEVER).build();
    private final KeycloakSession session;
    PlatformEmailSender(KeycloakSession session) { this.session = session; }
    @Override public void validate(Map<String, String> config) throws EmailException {
        var realm = session.getContext().getRealm();
        String id = realm == null ? null : realm.getAttribute("platform.environmentId");
        String secret = System.getenv("PLATFORM_MAIL_SECRET");
        if (realm == null || !realm.isEnabled() || id == null || secret == null || secret.length() < 32)
            throw new EmailException("Platform email delivery unavailable");
        try {
            if (!realm.getName().equals("p-" + UUID.fromString(id).toString().replace("-", ""))) throw new IllegalArgumentException();
        } catch (IllegalArgumentException error) { throw new EmailException("Platform email realm mismatch"); }
    }
    @Override public void send(Map<String, String> config, String address, String subject, String textBody, String htmlBody) throws EmailException {
        validate(config);
        var realm = session.getContext().getRealm();
        if (address == null || subject == null) throw new EmailException("Missing email recipient or subject");
        try {
            var message = Map.of("id", UUID.randomUUID().toString(), "environmentId", realm.getAttribute("platform.environmentId"),
                "realm", realm.getName(), "recipient", address, "subject", subject,
                "textBody", textBody == null ? "" : textBody, "htmlBody", htmlBody == null ? "" : htmlBody);
            var request = HttpRequest.newBuilder(URI.create("http://notification-service:8080/internal/v1/email"))
                .timeout(Duration.ofSeconds(25)).header("Content-Type", "application/json")
                .header("X-Platform-Mail-Key", System.getenv("PLATFORM_MAIL_SECRET"))
                .POST(HttpRequest.BodyPublishers.ofString(JsonSerialization.writeValueAsString(message))).build();
            int status = HTTP.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
            if (status < 200 || status >= 300) throw new EmailException("Platform email delivery not confirmed");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt(); throw new EmailException("Platform email delivery interrupted");
        } catch (EmailException error) { throw error;
        } catch (Exception error) {
            // Do not log bodies, email addresses or action-token links.
            throw new EmailException("Platform email delivery unavailable");
        }
    }
    @Override public void close() {}
}
