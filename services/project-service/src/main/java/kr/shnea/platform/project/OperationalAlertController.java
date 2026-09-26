package kr.shnea.platform.project;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import kr.shnea.platform.http.RequestTrace;

@RestController
class OperationalAlertController {
    record Alert(UUID id, UUID projectId, UUID environmentId, UUID jobId, String code, String errorCode,
                 String requestId, Instant occurredAt, Instant createdAt, Instant acknowledgedAt,
                 String acknowledgedBy, String acknowledgementRequestId) {}
    record Acknowledge(UUID projectId, UUID environmentId, String actor, String requestId) {}
    private final ProjectService projects;
    private final RestClient http;
    OperationalAlertController(ProjectService projects,@Value("${PLATFORM_EVENTS_SECRET:}") String secret,
            @Value("${NOTIFICATION_INTERNAL_URL:http://notification-service:8080}") String url) {
        this.projects=projects;
        var factory=new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
        factory.setReadTimeout(Duration.ofSeconds(5));
        http=RestClient.builder().baseUrl(url).requestFactory(factory).defaultHeader("X-Platform-Event-Key",secret)
            .requestInterceptor(RequestTrace.propagate()).build();
    }
    @GetMapping("/api/v1/admin/environments/{id}/operational-alerts")
    List<Alert> list(@PathVariable UUID id,@RequestParam(required=false) Boolean acknowledged,
            @RequestParam(defaultValue="20") int limit,@RequestParam(defaultValue="0") int offset) {
        if(limit<1 || limit>100 || offset<0 || offset>1_000_000)throw ApiCode.INVALID_PAGINATION.failure();
        var env=projects.findEnvironment(id);
        var rows=http.get().uri(builder->{
            var uri=builder.path("/internal/v1/operational-alerts").queryParam("projectId",env.projectId())
                .queryParam("environmentId",id).queryParam("limit",limit).queryParam("offset",offset);
            if(acknowledged!=null)uri.queryParam("acknowledged",acknowledged);
            return uri.build();
        }).retrieve().body(Alert[].class);
        if(rows==null)throw ApiCode.UPSTREAM_UNAVAILABLE.failure();
        return List.of(rows);
    }
    @PostMapping("/api/v1/admin/environments/{id}/operational-alerts/{eventId}/acknowledge")
    Alert acknowledge(@PathVariable UUID id,@PathVariable UUID eventId,@AuthenticationPrincipal Jwt actor,HttpServletRequest request) {
        var env=projects.findEnvironment(id);
        try {
            var result=http.post().uri("/internal/v1/operational-alerts/{id}/acknowledge",eventId)
                .body(new Acknowledge(env.projectId(),id,actor.getSubject(),RequestTrace.id(request))).retrieve().body(Alert.class);
            if(result==null)throw ApiCode.UPSTREAM_UNAVAILABLE.failure();
            return result;
        } catch(RestClientResponseException error) {
            if(error.getStatusCode().value()==404)throw ApiCode.RESOURCE_NOT_FOUND.failure();
            throw error;
        }
    }
}
