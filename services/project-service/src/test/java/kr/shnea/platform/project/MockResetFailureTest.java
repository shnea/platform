package kr.shnea.platform.project;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

class MockResetFailureTest {
    @Test void protectsChangedAccountsAndNeverDeletesAfterLogoutFailure() throws Exception {
        UUID environmentId = UUID.randomUUID(), userId = UUID.randomUUID();
        String realm = "p-" + environmentId.toString().replace("-", "");
        String username = "mock-" + "a".repeat(64);
        List<String> writes = new ArrayList<>();
        var fault = new AtomicReference<>("logout");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            int status = 200;
            String response;
            if (path.endsWith("/token")) response = "{\"access_token\":\"test-admin\"}";
            else if (path.equals("/admin/realms/" + realm))
                response = "{\"attributes\":{\"platform.environmentId\":\"" + environmentId + "\"}}";
            else if (path.endsWith("/users/profile")) response = "{\"attributes\":[{\"name\":\"platformMock\",\"permissions\":{\"edit\":[\""
                + (fault.get().equals("marker") ? "user" : "admin") + "\"]}}]}";
            else if (path.endsWith("/clients")) response = "[{\"id\":\"management-id\"}]";
            else if (method.equals("PUT")) { writes.add(body); status = 204; response = ""; }
            else if (path.endsWith("/logout")) { writes.add("logout"); status = 503; response = "{}"; }
            else if (method.equals("DELETE")) { writes.add("delete"); status = 204; response = ""; }
            else if (path.endsWith("/role-mappings")) response = "{}";
            else if (path.endsWith("/composite")) response = fault.get().equals("admin") ? "[{\"name\":\"realm-admin\"}]" : "[]";
            else if (path.endsWith("/groups")) response = fault.get().equals("group") ? "[{\"id\":\"group\"}]" : "[]";
            else if (path.endsWith("/federated-identity")) response = "[]";
            else if (path.endsWith("/users")) response = "[{\"id\":\"" + userId + "\"}]";
            else response = "{\"id\":\"" + userId + "\",\"username\":\"" + username
                + "\",\"email\":\"" + username.substring(5) + "@example.invalid\",\"firstName\":\"Mock\",\"lastName\":\"google\","
                + "\"attributes\":{\"platformMock\":[\"" + (fault.get().equals("ordinary") ? "false" : "true") + "\"]}}";
            byte[] data = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, status == 204 ? -1 : data.length);
            if (status != 204) exchange.getResponseBody().write(data);
            exchange.close();
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort();
            var client = new IdentityClient(url, url, "test", "dev", org.mockito.Mockito.mock(EmailClient.class));
            var env = new ProjectService.Environment(environmentId, UUID.randomUUID(), "dev", "DEV", realm, false, List.of(), "READY", url, 0);
            assertThrows(RestClientException.class, () -> client.deleteMockUser(env, userId));
            assertEquals(List.of("{\"enabled\":false}", "logout"), writes);
            writes.clear();
            for (String changed : List.of("marker", "admin", "group", "ordinary")) {
                fault.set(changed);
                assertThrows(ResponseStatusException.class, () -> client.deleteMockUser(env, userId), changed);
                assertTrue(writes.isEmpty(), changed);
            }
            var prod = new IdentityClient(url, url, "test", "prod", org.mockito.Mockito.mock(EmailClient.class));
            assertThrows(ResponseStatusException.class, () -> prod.mockResetPreview(env));
            var foreign = new ProjectService.Environment(UUID.randomUUID(), env.projectId(), "dev", "DEV", realm, false, List.of(), "READY", url, 0);
            assertThrows(ResponseStatusException.class, () -> client.deleteMockUser(foreign, userId));
            assertTrue(writes.isEmpty());
        } finally { server.stop(0); }
    }
}
