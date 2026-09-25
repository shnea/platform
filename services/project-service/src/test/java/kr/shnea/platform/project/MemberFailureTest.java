package kr.shnea.platform.project;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

class MemberFailureTest {
    @Test void logoutFailureLeavesAccountDisabledAndReportsFailure() throws Exception {
        UUID environmentId = UUID.randomUUID(), userId = UUID.randomUUID();
        List<String> writes = new ArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            int status = 200;
            String response;
            if (path.endsWith("/token")) response = "{\"access_token\":\"test-admin\"}";
            else if (path.equals("/admin/realms/test/users"))
                response = "[{\"id\":\"" + userId + "\"}]";
            else if (path.equals("/admin/realms/test"))
                response = "{\"attributes\":{\"platform.environmentId\":\"" + environmentId + "\"}}";
            else if (path.endsWith("/logout")) {
                writes.add("logout"); status = 503; response = "{}";
            } else if (method.equals("PUT")) {
                writes.add(body); status = 204; response = "";
            } else response = "{\"id\":\"" + userId + "\",\"username\":\"test-user\",\"enabled\":true}";
            byte[] data = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, status == 204 ? -1 : data.length);
            if (status != 204) exchange.getResponseBody().write(data);
            exchange.close();
        });
        server.start();
        try {
            var client = new IdentityClient("http://127.0.0.1:" + server.getAddress().getPort(), "http://localhost/auth", "test-secret", "dev", org.mockito.Mockito.mock(EmailClient.class));
            var env = new ProjectService.Environment(environmentId, UUID.randomUUID(), "dev", "DEV", "test",
                false, List.of(), "READY", "http://localhost/auth/realms/test", 0);
            assertThrows(RestClientException.class, () -> client.updateMember(env, userId, false, true));
            assertEquals(List.of("{\"enabled\":false}", "logout"), writes);
            writes.clear();
            // A stale expectation must not write or log out anyone.
            var conflict = assertThrows(ResponseStatusException.class, () -> client.updateMember(env, userId, false, false));
            assertEquals(409, conflict.getStatusCode().value());
            assertTrue(writes.isEmpty());
            var foreign = new ProjectService.Environment(UUID.randomUUID(), env.projectId(), "dev", "DEV", "test",
                false, List.of(), "READY", env.issuer(), 0);
            assertThrows(ResponseStatusException.class, () -> client.updateMember(foreign, userId, false, true));
            assertTrue(writes.isEmpty());
        } finally { server.stop(0); }
    }
}
