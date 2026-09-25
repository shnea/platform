package kr.shnea.platform.project;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
class EmailClient {
    private final RestClient http;
    private final String mode;
    EmailClient(@Value("${PLATFORM_MAIL_SECRET}") String secret, @Value("${platform.mode}") String mode,
                @Value("${NOTIFICATION_INTERNAL_URL:http://notification-service:8080}") String url) {
        this.mode = mode;
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
        factory.setReadTimeout(Duration.ofSeconds(5));
        http = RestClient.builder().baseUrl(url).requestFactory(factory).defaultHeader("X-Platform-Mail-Key", secret)
            .requestInterceptor(kr.shnea.platform.http.RequestTrace.propagate()).build();
    }
    String delivery(ProjectService.Environment env) {
        if (!(mode.equals("dev") && env.kind().equals("DEV")) && !(mode.equals("prod") && env.kind().equals("PROD"))) return "UNAVAILABLE";
        try {
            Map<?, ?> state = http.get().uri("/internal/v1/email/readiness").retrieve().body(Map.class);
            if (state != null && mode.equals(state.get("mode")) && Boolean.TRUE.equals(state.get("ready")))
                return mode.equals("dev") ? "MOCK" : "NCP";
        } catch (RestClientException error) { /* Keep policy readable while delivery is unavailable. */ }
        return "UNAVAILABLE";
    }
    List<?> inbox(UUID id) {
        return http.get().uri("/internal/v1/email/inbox/{id}", id).retrieve().body(List.class);
    }
}
