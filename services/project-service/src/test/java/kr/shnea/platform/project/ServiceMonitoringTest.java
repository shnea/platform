package kr.shnea.platform.project;

import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import kr.shnea.platform.http.ServiceTelemetry;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class ServiceMonitoringTest {
    private static final String SECRET="m".repeat(48);
    @Test void aggregatesOnlyApplicationRequestsAndSeparatesEmptyFromZero() {
        var registry=new SimpleMeterRegistry(); try {
            var telemetry=new ServiceTelemetry(registry,SECRET);
            var empty=telemetry.read(SECRET).getBody();
            assertThat(empty.requests()).isZero(); assertThat(empty.averageMs()).isNull(); assertThat(empty.serverErrorPercent()).isNull();
            Timer.builder("http.server.requests").tags("uri","/api/v1/projects/{id}","status","200").register(registry).record(Duration.ofMillis(100));
            Timer.builder("http.server.requests").tags("uri","UNKNOWN","status","401").register(registry).record(Duration.ofMillis(20));
            Timer.builder("http.server.requests").tags("uri","/internal/v1/email","status","503").register(registry).record(Duration.ofMillis(180));
            for(String uri:new String[]{"/actuator/health/readiness","/internal/v1/monitoring","/api/v1/admin/service-metrics"})
                Timer.builder("http.server.requests").tags("uri",uri,"status","200").register(registry).record(Duration.ofSeconds(10));
            var snapshot=telemetry.read(SECRET).getBody();
            assertThat(snapshot.requests()).isEqualTo(3);assertThat(snapshot.clientErrors()).isEqualTo(1);assertThat(snapshot.serverErrors()).isEqualTo(1);
            assertThat(snapshot.averageMs()).isEqualTo(100);assertThat(snapshot.serverErrorPercent()).isCloseTo(100.0/3,within(0.001));
            assertThat(snapshot.instanceId()).isEqualTo(empty.instanceId());
            assertThat(new ServiceTelemetry(registry,SECRET).read(SECRET).getBody().instanceId()).isNotEqualTo(empty.instanceId());
            assertThat(telemetry.read(SECRET).getHeaders().getCacheControl()).isEqualTo("no-store");
        } finally { registry.close(); }
    }
    @Test void requiresDedicatedSecretAndRejectsUnconfiguredOrShortKeys() {
        var registry=new SimpleMeterRegistry(); try {
            var telemetry=new ServiceTelemetry(registry,SECRET);
            for(String key:new String[]{null,"", "mail-key", "x".repeat(300)})
                assertThatThrownBy(()->telemetry.read(key)).isInstanceOf(ResponseStatusException.class)
                    .satisfies(e->assertThat(((ResponseStatusException)e).getStatusCode().value()).isEqualTo(403));
            assertThatThrownBy(()->new ServiceTelemetry(registry,"").read(SECRET)).isInstanceOf(ResponseStatusException.class);
            assertThatThrownBy(()->new ServiceTelemetry(registry,"short")).isInstanceOf(IllegalArgumentException.class);
        } finally { registry.close(); }
    }
    @Test void probesHealthAndMetricsIndependentlyAndDoesNotFollowRedirects() throws Exception {
        var json=new JsonMapper();var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        AtomicInteger healthCode=new AtomicInteger(200),metricsCode=new AtomicInteger(200),mode=new AtomicInteger(0),metricCalls=new AtomicInteger();
        var registry=new SimpleMeterRegistry(); try {
            var telemetry=new ServiceTelemetry(registry,SECRET);
            server.createContext("/actuator/health/readiness",exchange->{
                var body=(healthCode.get()==200?"{\"status\":\"UP\"}":"{\"status\":\"DOWN\"}").getBytes();
                exchange.sendResponseHeaders(healthCode.get(),body.length);exchange.getResponseBody().write(body);exchange.close();
            });
            server.createContext("/internal/v1/monitoring",exchange->{
                metricCalls.incrementAndGet();
                assertThat(exchange.getRequestHeaders().getFirst("X-Platform-Monitoring-Key")).isEqualTo(SECRET);
                byte[] body=(mode.get()==0?json.writeValueAsString(telemetry.read(SECRET).getBody()):"{}").getBytes();
                exchange.getResponseHeaders().add("Location","http://127.0.0.1:1/must-not-follow");
                exchange.sendResponseHeaders(metricsCode.get(),body.length);exchange.getResponseBody().write(body);exchange.close();
            });server.start();
            String base="http://127.0.0.1:"+server.getAddress().getPort();var monitor=new ServiceMonitoring(json,SECRET);
            var ok=monitor.probe("test",base);assertThat(ok.status()).isEqualTo("UP");assertThat(ok.metricsStatus()).isEqualTo("AVAILABLE");
            healthCode.set(503);var down=monitor.probe("test",base);assertThat(down.status()).isEqualTo("DOWN");assertThat(down.metrics()).isNotNull();
            metricsCode.set(403);var denied=monitor.probe("test",base);assertThat(denied.metrics()).isNull();assertThat(denied.metricsStatus()).isEqualTo("UNAVAILABLE");
            metricsCode.set(302);assertThat(monitor.probe("test",base).metrics()).isNull();
            metricsCode.set(200);mode.set(1);assertThat(monitor.probe("test",base).metrics()).isNull();
            int before=metricCalls.get();assertThat(new ServiceMonitoring(json,"").probe("test",base).metricsStatus()).isEqualTo("DISABLED");assertThat(metricCalls.get()).isEqualTo(before);
            server.stop(0);var missing=monitor.probe("test",base);assertThat(missing.status()).isEqualTo("UNREACHABLE");assertThat(missing.metrics()).isNull();
        } finally {server.stop(0);registry.close();}
    }
}
