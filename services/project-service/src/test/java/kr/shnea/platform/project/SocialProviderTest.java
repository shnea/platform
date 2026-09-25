package kr.shnea.platform.project;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

class SocialProviderTest {
    private final JsonMapper json = new JsonMapper();
    private final UUID environmentId = UUID.randomUUID();
    private final Map<String, Map<String, Object>> providers = new HashMap<>();
    private HttpServer server;
    private IdentityClient client;
    private ProjectService.Environment env;
    private String lastSubmittedSecret;
    private boolean owned = true;

    @BeforeEach void setup() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            Object result = Map.of();
            int status = 200;
            if (path.endsWith("/token")) result = Map.of("access_token", "test-admin");
            else if (path.equals("/admin/realms/test")) result = Map.of("attributes",
                Map.of("platform.environmentId", owned ? environmentId.toString() : "other-environment"));
            else if (path.contains("/identity-provider/instances")) {
                if (List.of("POST", "PUT").contains(exchange.getRequestMethod())) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> representation = json.readValue(exchange.getRequestBody().readAllBytes(), Map.class);
                    @SuppressWarnings("unchecked")
                    Map<String, String> config = (Map<String, String>) representation.get("config");
                    lastSubmittedSecret = config.get("clientSecret");
                    config.put("clientSecret", "**********");
                    representation.put("internalId", "test-internal-id");
                    providers.put((String) representation.get("alias"), representation);
                    status = 204;
                } else {
                    result = providers.get(path.substring(path.lastIndexOf('/') + 1));
                    if (result == null) status = 404;
                }
            } else status = 404;
            byte[] body = json.writeValueAsBytes(result);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, status == 204 ? -1 : body.length);
            if (status != 204) exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        client = client("prod");
        env = new ProjectService.Environment(environmentId, UUID.randomUUID(), "prod", "PROD", "test",
            false, List.of("https://example.invalid/cb"), "READY", "https://platform.example/auth/realms/test", 0);
    }

    private IdentityClient client(String mode) {
        return new IdentityClient("http://127.0.0.1:" + server.getAddress().getPort(), "https://platform.example/auth", "test-secret", mode);
    }
    @AfterEach void stop() { server.stop(0); }

    @Test void secretsAreNeverReturnedAndBlankSecretPreservesKeycloakMask() {
        for (SocialProvider provider : SocialProvider.ALL) {
            var first = client.updateSocialProvider(env, provider,
                new ProjectController.SocialSettings("client-one", "secret-one", true, "unconfigured"));
            assertTrue(first.enabled());
            assertTrue(first.secretConfigured());
            assertEquals(env.issuer() + "/broker/" + provider.alias() + "/endpoint", first.callbackUrl());
            String output = json.writeValueAsString(first);
            assertFalse(output.contains("secret-one"));
            assertFalse(output.contains("**********"));
            var second = client.updateSocialProvider(env, provider,
                new ProjectController.SocialSettings("client-one", null, false, first.revision()));
            assertEquals("**********", lastSubmittedSecret);
            assertNotEquals(first.revision(), second.revision());
            assertFalse(second.enabled());
            assertStatus(409, () -> client.updateSocialProvider(env, provider,
                new ProjectController.SocialSettings("client-one", "stale-secret", true, first.revision())));
            assertStatus(400, () -> client.updateSocialProvider(env, provider,
                new ProjectController.SocialSettings("different-client", null, false, second.revision())));
            var stored = providers.get(provider.alias());
            assertEquals(false, stored.get("trustEmail"));
            assertEquals(false, stored.get("storeToken"));
            assertEquals("first broker login", stored.get("firstBrokerLoginFlowAlias"));
        }
    }

    @Test void rejectsMissingSecretDevelopmentActivationAndForeignOwnership() {
        var provider = SocialProvider.find("google");
        assertStatus(400, () -> client.updateSocialProvider(env, provider,
            new ProjectController.SocialSettings("client", null, false, "unconfigured")));
        assertStatus(400, () -> client("dev").updateSocialProvider(env, provider,
            new ProjectController.SocialSettings("client", "secret", true, "unconfigured")));
        var dev = new ProjectService.Environment(environmentId, env.projectId(), "dev", "DEV", "test",
            false, env.redirectUris(), "READY", env.issuer(), 0);
        assertStatus(400, () -> client.updateSocialProvider(dev, provider,
            new ProjectController.SocialSettings("client", "secret", true, "unconfigured")));
        assertStatus(400, () -> SocialProvider.find("arbitrary-provider"));
        providers.put(provider.alias(), Map.of("providerId", "google", "config", Map.of("clientId", "foreign")));
        assertStatus(409, () -> client.socialProviders(env));
        owned = false;
        assertStatus(409, () -> client.socialProviders(env));
    }

    @Test void kakaoValidatesSignedOidcTokensUsingFixedEndpoints() {
        Map<String, String> config = SocialProvider.find("kakao").config();
        assertEquals("true", config.get("validateSignature"));
        assertEquals("true", config.get("useJwksUrl"));
        assertEquals("https://kauth.kakao.com", config.get("issuer"));
        assertEquals("S256", config.get("pkceMethod"));
    }

    private static void assertStatus(int expected, Runnable action) {
        assertEquals(expected, assertThrows(ResponseStatusException.class, action::run).getStatusCode().value());
    }
}
