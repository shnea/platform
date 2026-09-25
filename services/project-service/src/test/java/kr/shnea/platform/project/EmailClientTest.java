package kr.shnea.platform.project;

import static org.junit.jupiter.api.Assertions.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class EmailClientTest {
    @Test void onlyMatchingReadyDeliveryModesEnableActionsAndOutagesFailClosed() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var state = new AtomicReference<>("{\"mode\":\"dev\",\"ready\":true}");
        server.createContext("/internal/v1/email/readiness", exchange -> {
            assertEquals("test-secret", exchange.getRequestHeaders().getFirst("X-Platform-Mail-Key"));
            byte[] body = state.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body); exchange.close();
        });
        server.start();
        try {
            String url = "http://127.0.0.1:"+server.getAddress().getPort();
            var dev = new EmailClient("test-secret", "dev", url);
            var prod = new EmailClient("test-secret", "prod", url);
            assertEquals("MOCK", dev.delivery(environment("DEV")));
            assertEquals("UNAVAILABLE", dev.delivery(environment("PROD")));
            assertEquals("UNAVAILABLE", prod.delivery(environment("DEV")));
            assertEquals("UNAVAILABLE", prod.delivery(environment("PROD")));
            state.set("{\"mode\":\"prod\",\"ready\":false}");
            assertEquals("UNAVAILABLE", prod.delivery(environment("PROD")));
            state.set("{\"mode\":\"prod\",\"ready\":true}");
            assertEquals("NCP", prod.delivery(environment("PROD")));
            assertEquals("UNAVAILABLE", dev.delivery(environment("DEV")));
            server.stop(0);
            assertEquals("UNAVAILABLE", prod.delivery(environment("PROD")));
        } finally { server.stop(0); }
    }
    private ProjectService.Environment environment(String kind) {
        return new ProjectService.Environment(UUID.randomUUID(), UUID.randomUUID(), "test", kind, "test", false, List.of(), "READY", "test", 0);
    }
}
