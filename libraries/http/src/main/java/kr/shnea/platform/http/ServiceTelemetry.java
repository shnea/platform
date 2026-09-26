package kr.shnea.platform.http;

import io.micrometer.core.instrument.MeterRegistry;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

/** Aggregate only: never return routes, labels, request data or configuration. */
@RestController
public class ServiceTelemetry {
    public record Snapshot(String instanceId, Instant startedAt, Instant measuredAt, long requests,
            long clientErrors, long serverErrors, Double serverErrorPercent, Double averageMs) {}
    private final MeterRegistry registry;
    private final byte[] secret;
    private final String instanceId = UUID.randomUUID().toString();
    private final Instant startedAt = Instant.ofEpochMilli(ManagementFactory.getRuntimeMXBean().getStartTime());
    public ServiceTelemetry(MeterRegistry registry, @Value("${PLATFORM_MONITORING_SECRET:}") String secret) {
        this.registry=registry; this.secret=secret.getBytes(StandardCharsets.UTF_8);
        if (!secret.isEmpty() && secret.length()<32) throw new IllegalArgumentException("Configure PLATFORM_MONITORING_SECRET (32+ characters)");
    }
    @GetMapping("/internal/v1/monitoring")
    public ResponseEntity<Snapshot> read(@RequestHeader(value="X-Platform-Monitoring-Key",required=false) String provided) {
        if (secret.length<32 || provided==null || provided.length()>256 || !MessageDigest.isEqual(secret,provided.getBytes(StandardCharsets.UTF_8)))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        long count=0, client=0, server=0; double total=0;
        for (var timer:registry.find("http.server.requests").timers()) {
            String uri=timer.getId().getTag("uri"), status=timer.getId().getTag("status");
            if (uri!=null && (uri.startsWith("/actuator") || uri.equals("/internal/v1/monitoring") || uri.equals("/api/v1/admin/service-metrics"))) continue;
            long n=timer.count(); count+=n; total+=timer.totalTime(TimeUnit.MILLISECONDS);
            if (status!=null && status.startsWith("4")) client+=n;
            if (status!=null && status.startsWith("5")) server+=n;
        }
        // Micrometer counters are cumulative per process, not a rolling availability/SLA window.
        return ResponseEntity.ok().header("Cache-Control","no-store").body(new Snapshot(instanceId,startedAt,Instant.now(),
            count,client,server,count==0?null:server*100.0/count,count==0?null:total/count));
    }
}
