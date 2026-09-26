package kr.shnea.platform.file;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;
import kr.shnea.platform.http.RequestTrace;
import org.slf4j.MDC;

@Component
class FileAccess {
    record Context(UUID projectId, UUID environmentId, UUID credentialId, String ownerKind) {
        Context(UUID projectId, UUID environmentId, UUID credentialId) { this(projectId, environmentId, credentialId, "CREDENTIAL"); }
        Context { if (ownerKind == null) ownerKind = "CREDENTIAL"; }
        String actor() { return ownerKind.toLowerCase(java.util.Locale.ROOT) + ":" + credentialId; }
    }
    private final String secret;
    private final String base;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
        .followRedirects(HttpClient.Redirect.NEVER).build();
    private final JsonMapper json = new JsonMapper();
    FileAccess(@Value("${PLATFORM_FILES_SECRET:}") String secret,
               @Value("${platform.project-url:http://project-service:8080}") String base) {
        this.secret = secret; this.base = base;
    }
    Context require(String key, String scope) {
        if (key == null || key.length() > 150 || !key.matches("pk_[a-fA-F0-9-]{36}_[A-Za-z0-9_-]+"))
            throw new FileFailure("INVALID_API_KEY", 401, "파일 권한이 있는 서버 API 키가 필요합니다.");
        var request = request("/internal/v1/files/access?scope=" + scope)
            .header("X-Platform-Key", key).POST(HttpRequest.BodyPublishers.noBody()).build();
        var result = send(request);
        if (result.statusCode() == 401) throw new FileFailure("INVALID_API_KEY", 401, "API 키의 만료·폐기 여부와 프로젝트 상태를 확인해 주세요.");
        if (result.statusCode() == 403) throw new FileFailure("INSUFFICIENT_SCOPE", 403, "API 키에 필요한 파일 권한이 없습니다.");
        if (result.statusCode() != 200) throw FileFailure.unavailable();
        try {
            Context context = json.readValue(result.body(), Context.class);
            if (context.projectId() == null || context.environmentId() == null || context.credentialId() == null) throw FileFailure.unavailable();
            return context;
        } catch (RuntimeException e) { throw FileFailure.unavailable(); }
    }
    void requireActive(UUID environmentId) {
        var result = send(request("/internal/v1/files/environments/" + environmentId).GET().build());
        if (result.statusCode() == 404) throw FileFailure.missing();
        if (result.statusCode() != 200) throw FileFailure.unavailable();
        try {
            if (!json.readTree(result.body()).path("active").asBoolean(false)) throw FileFailure.missing();
        } catch (FileFailure e) { throw e; }
        catch (RuntimeException e) { throw FileFailure.unavailable(); }
    }
    Context administrator(UUID environmentId, String subject) {
        var result = send(request("/internal/v1/files/environments/" + environmentId).GET().build());
        if (result.statusCode() == 404) throw FileFailure.missing();
        if (result.statusCode() != 200) throw FileFailure.unavailable();
        try {
            var data = json.readTree(result.body());
            if (!data.path("active").asBoolean(false))
                throw new FileFailure("FILE_ENVIRONMENT_UNAVAILABLE",409,"프로젝트가 사용 중이고 환경 설정이 완료되어야 파일을 관리할 수 있습니다.");
            return new Context(UUID.fromString(data.path("projectId").asText()),environmentId,UUID.fromString(subject),"ADMIN");
        } catch (FileFailure e) { throw e; }
        catch (RuntimeException e) { throw FileFailure.unavailable(); }
    }
    private HttpRequest.Builder request(String path) {
        if (secret.length() < 32) throw FileFailure.unavailable();
        var builder = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(3))
            .header("X-Platform-Files-Key", secret);
        String id = MDC.get("requestId");
        if (id != null) builder.header(RequestTrace.HEADER, id);
        return builder;
    }
    private HttpResponse<String> send(HttpRequest request) {
        try { return client.send(request, HttpResponse.BodyHandlers.ofString()); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw FileFailure.unavailable(); }
        catch (java.io.IOException e) { throw FileFailure.unavailable(); }
    }
}
