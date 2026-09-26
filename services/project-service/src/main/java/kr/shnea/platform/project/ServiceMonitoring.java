package kr.shnea.platform.project;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import kr.shnea.platform.http.ServiceTelemetry.Snapshot;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

@RestController
class ServiceMonitoring {
    record Reading(String id, String status, Instant checkedAt, String metricsStatus, Snapshot metrics) {}
    record Report(Instant measuredAt, List<Reading> services) {}
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).followRedirects(HttpClient.Redirect.NEVER).build();
    private final JsonMapper json;
    private final String secret;
    private Report cached;
    private long cacheTime;
    ServiceMonitoring(JsonMapper json,@Value("${PLATFORM_MONITORING_SECRET:}") String secret) { this.json=json; this.secret=secret; }
    @jakarta.annotation.PreDestroy void close() { http.close(); }

    @GetMapping("/api/v1/admin/service-metrics")
    synchronized ResponseEntity<Report> read() {
        if (cached==null || System.nanoTime()-cacheTime>Duration.ofSeconds(10).toNanos()) {
            // Fixed Compose services: no caller-controlled URL, no cross-service database access.
            var services=List.of(probe("project-service","http://localhost:8080"),probe("file-service","http://file-service:8080"),
                probe("notification-service","http://notification-service:8080"));
            cached=new Report(Instant.now(),services); cacheTime=System.nanoTime();
        }
        return ResponseEntity.ok().header("Cache-Control","no-store").body(cached);
    }
    Reading probe(String id,String base) {
        String state="UNREACHABLE", metricState=secret.length()<32?"DISABLED":"UNAVAILABLE"; Snapshot metrics=null;
        try {
            var result=http.send(HttpRequest.newBuilder(URI.create(base+"/actuator/health/readiness")).timeout(Duration.ofSeconds(1)).GET().build(),HttpResponse.BodyHandlers.ofString());
            state="UNKNOWN";
            String health=json.readTree(result.body()).path("status").asText();
            state=result.statusCode()==200 && health.equals("UP")?"UP":result.statusCode()==503 && (health.equals("DOWN") || health.equals("OUT_OF_SERVICE"))?"DOWN":"UNKNOWN";
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        catch (Exception ignored) { /* A failed probe is not proof of the root cause. */ }
        if (secret.length()>=32 && !Thread.currentThread().isInterrupted()) {
            try {
                var result=http.send(HttpRequest.newBuilder(URI.create(base+"/internal/v1/monitoring")).timeout(Duration.ofSeconds(1))
                    .header("X-Platform-Monitoring-Key",secret).GET().build(),HttpResponse.BodyHandlers.ofString());
                if (result.statusCode()==200) {
                    var value=json.readValue(result.body(),Snapshot.class);
                    if (valid(value)) { metrics=value; metricState="AVAILABLE"; }
                }
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            catch (Exception ignored) { /* Keep availability and missing metrics separate; no fake zero. */ }
        }
        return new Reading(id,state,Instant.now(),metricState,metrics);
    }
    private boolean valid(Snapshot v) {
        return v!=null && v.instanceId()!=null && v.startedAt()!=null && v.measuredAt()!=null && !v.startedAt().isAfter(v.measuredAt())
            && v.requests()>=0 && v.clientErrors()>=0 && v.serverErrors()>=0 && v.clientErrors()+v.serverErrors()<=v.requests()
            && (v.requests()==0?v.averageMs()==null && v.serverErrorPercent()==null:
                v.averageMs()!=null && Double.isFinite(v.averageMs()) && v.averageMs()>=0 && v.serverErrorPercent()!=null
                && Double.isFinite(v.serverErrorPercent()) && v.serverErrorPercent()>=0 && v.serverErrorPercent()<=100);
    }
}
