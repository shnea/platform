package kr.shnea.platform.file;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class FileAccessTest {
    @Test void realHttpClientValidatesEveryRequestAndFailsClosedOnRedirectOrUnavailableProject() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var calls = new AtomicInteger(); var status = new AtomicInteger(200);
        String secret = "s".repeat(32), key = "pk_"+UUID.randomUUID()+"_opaque";
        UUID project = UUID.randomUUID(), environment = UUID.randomUUID(), credential = UUID.randomUUID();
        server.createContext("/internal/v1/files/access", exchange -> {
            calls.incrementAndGet();
            if (!secret.equals(exchange.getRequestHeaders().getFirst("X-Platform-Files-Key"))
                    || !key.equals(exchange.getRequestHeaders().getFirst("X-Platform-Key"))
                    || !exchange.getRequestURI().getQuery().equals("scope=files:read")) {
                exchange.sendResponseHeaders(403, -1); exchange.close(); return;
            }
            var bytes = ("{\"projectId\":\""+project+"\",\"environmentId\":\""+environment+"\",\"credentialId\":\""+credential+"\"}").getBytes();
            exchange.getResponseHeaders().add("Location", "/must-not-follow");
            exchange.sendResponseHeaders(status.get(), bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.createContext("/internal/v1/files/environments/", exchange -> {
            var bytes = "{\"active\":false}".getBytes();
            exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
        var client = new FileAccess(secret, "http://127.0.0.1:"+server.getAddress().getPort());
        try {
            assertThat(client.require(key, "files:read")).isEqualTo(new FileAccess.Context(project, environment, credential));
            client.require(key, "files:read"); assertThat(calls.get()).isEqualTo(2); // No stale credential cache.
            status.set(401); FilesDatabaseTest.code("INVALID_API_KEY", () -> client.require(key, "files:read"));
            status.set(403); FilesDatabaseTest.code("INSUFFICIENT_SCOPE", () -> client.require(key, "files:read"));
            status.set(302); FilesDatabaseTest.code("FILE_SERVICE_UNAVAILABLE", () -> client.require(key, "files:read"));
            status.set(503); FilesDatabaseTest.code("FILE_SERVICE_UNAVAILABLE", () -> client.require(key, "files:read"));
            FilesDatabaseTest.code("FILE_NOT_FOUND", () -> client.requireActive(environment));
            FilesDatabaseTest.code("INVALID_API_KEY", () -> client.require("\r\nforged", "files:read"));
            FilesDatabaseTest.code("FILE_SERVICE_UNAVAILABLE", () -> new FileAccess("", "http://127.0.0.1:1").require(key, "files:read"));
        } finally { server.stop(0); }
        FilesDatabaseTest.code("FILE_SERVICE_UNAVAILABLE", () -> client.require(key, "files:read"));
    }
}
