package kr.shnea.platform.notification;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
class NcpMail {
    private final String access, secret, sender, senderName;
    private final URI endpoint;
    private final HttpClient http;
    private final JsonMapper json = new JsonMapper();

    @org.springframework.beans.factory.annotation.Autowired
    NcpMail(Environment env) {
        this(env, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build());
    }
    NcpMail(Environment env, HttpClient http) {
        this.http = http;
        access = env.getProperty("NCP_ACCESS_KEY", ""); secret = env.getProperty("NCP_SECRET_KEY", "");
        sender = env.getProperty("NCP_MAIL_SENDER_ADDRESS", ""); senderName = env.getProperty("NCP_MAIL_SENDER_NAME", "SHNEA");
        String host = env.getProperty("NCP_MAIL_API_ENDPOINT", "https://mail.apigw.ntruss.com");
        String path = env.getProperty("NCP_MAIL_API_PATH", "/api/v1/mails");
        if (!host.equals("https://mail.apigw.ntruss.com") || !path.equals("/api/v1/mails"))
            throw new IllegalArgumentException("Unsupported NCP mail endpoint");
        endpoint = URI.create(host + path);
    }
    boolean ready() { return !access.isBlank() && !secret.isBlank() && sender.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+") && senderName.length() <= 100; }
    static String signature(String method, String path, String timestamp, String access, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return Base64.getEncoder().encodeToString(mac.doFinal((method + " " + path + "\n" + timestamp + "\n" + access).getBytes(StandardCharsets.UTF_8)));
    }
    record Outcome(String state, String providerId) {}
    Outcome send(EmailController.Message message) {
        return send(message.recipient(),message.subject(),message.textBody(),message.htmlBody());
    }
    Outcome send(String recipient,String subject,String textBody) { return send(recipient,subject,textBody,""); }
    private Outcome send(String recipient,String subject,String textBody,String htmlBody) {
        try {
            String timestamp = Long.toString(System.currentTimeMillis());
            var body = Map.of("senderAddress", sender, "senderName", senderName, "title", subject,
                "body", htmlBody.isBlank() ? escape(textBody) : htmlBody,
                "recipients", List.of(Map.of("address", recipient, "type", "R")),
                "individual", true, "advertising", false);
            var request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json").header("x-ncp-iam-access-key", access)
                .header("x-ncp-apigw-timestamp", timestamp)
                .header("x-ncp-apigw-signature-v2", signature("POST", endpoint.getRawPath(), timestamp, access, secret))
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                var data = json.readTree(response.body());
                String id = data.path("requestId").asText("");
                if (!id.isBlank() && id.length() <= 128 && data.path("count").asInt() == 1) return new Outcome("ACCEPTED", id);
            }
            // 5xx/timeouts may have accepted the request: never automatically resend.
            return new Outcome(response.statusCode() >= 400 && response.statusCode() < 500 ? "FAILED" : "UNKNOWN", null);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt(); return new Outcome("UNKNOWN", null);
        } catch (Exception error) {
            // Provider bodies and exceptions may contain recipients, tokens or credentials.
            return new Outcome("UNKNOWN", null);
        }
    }
    private static String escape(String value) {
        return "<pre>" + value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;") + "</pre>";
    }
}
