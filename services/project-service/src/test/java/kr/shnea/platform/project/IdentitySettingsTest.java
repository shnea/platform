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

class IdentitySettingsTest {
    private final JsonMapper json = new JsonMapper();
    private final UUID environmentId = UUID.randomUUID();
    private final Map<String, Map<String, Object>> providers = new HashMap<>();
    private HttpServer server;
    private IdentityClient client;
    private ProjectService.Environment env;
    private boolean credentialsReady = true;
    private boolean owned = true;
    private final Map<String, Object> realmSettings = new HashMap<>();

    @BeforeEach void setup() throws Exception {
        realmSettings.put("attributes", Map.of("platform.environmentId", environmentId.toString()));
        realmSettings.put("enabled", false);
        realmSettings.put("registrationAllowed", true);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            Object result = Map.of();
            int status = 200;
            if (path.endsWith("/token")) result = Map.of("access_token", "test-admin");
            else if (path.equals("/realms/master/platform-social/configuration")) result = Map.of("naver", credentialsReady, "kakao", credentialsReady, "google", credentialsReady);
            else if (path.equals("/admin/realms/test")) {
                if (exchange.getRequestMethod().equals("PUT")) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> update = json.readValue(exchange.getRequestBody().readAllBytes(), Map.class);
                    realmSettings.putAll(update);
                    status = 204;
                } else {
                    result = owned ? realmSettings : Map.of("attributes", Map.of("platform.environmentId", "other-environment"));
                }
            }
            else if (path.contains("/identity-provider/instances")) {
                if (List.of("POST", "PUT").contains(exchange.getRequestMethod())) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> representation = json.readValue(exchange.getRequestBody().readAllBytes(), Map.class);
                    @SuppressWarnings("unchecked")
                    Map<String, String> config = (Map<String, String>) representation.get("config");
                    assertFalse(config.containsKey("clientSecret"));
                    assertFalse(config.containsKey("clientId"));
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

    @Test void commonCredentialsStayOutOfRealmSettingsAndApiResponses() {
        for (SocialProvider provider : SocialProvider.ALL) {
            var first = client.updateSocialProvider(env, provider,
                new ProjectController.SocialSettings(true, "unconfigured"));
            assertTrue(first.enabled());
            assertTrue(first.credentialsConfigured());
            assertEquals("https://platform.example/auth/social/" + provider.code() + "/callback", first.callbackUrl());
            assertTrue(first.sharedCallback());
            String output = json.writeValueAsString(first);
            assertFalse(output.contains("secret-one"));
            assertFalse(output.contains("**********"));
            var second = client.updateSocialProvider(env, provider,
                new ProjectController.SocialSettings(false, first.revision()));
            assertNotEquals(first.revision(), second.revision());
            assertFalse(second.enabled());
            assertStatus(409, () -> client.updateSocialProvider(env, provider,
                new ProjectController.SocialSettings(true, first.revision())));
            assertFalse(json.writeValueAsString(providers).contains("clientSecret"));
            var stored = providers.get(provider.alias());
            assertEquals(false, stored.get("trustEmail"));
            assertEquals(false, stored.get("storeToken"));
            assertEquals("first broker login", stored.get("firstBrokerLoginFlowAlias"));
        }
    }

    @Test void rejectsMissingCommonCredentialsDevelopmentActivationAndForeignOwnership() {
        var provider = SocialProvider.find("google");
        credentialsReady = false;
        assertStatus(400, () -> client.updateSocialProvider(env, provider, new ProjectController.SocialSettings(true, "unconfigured")));
        credentialsReady = true;
        assertStatus(400, () -> client("dev").updateSocialProvider(env, provider,
            new ProjectController.SocialSettings(true, "unconfigured")));
        var dev = new ProjectService.Environment(environmentId, env.projectId(), "dev", "DEV", "test",
            false, env.redirectUris(), "READY", env.issuer(), 0);
        assertStatus(400, () -> client.updateSocialProvider(dev, provider,
            new ProjectController.SocialSettings(true, "unconfigured")));
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

    @Test void authenticationPoliciesPreserveRealmStateAndRequireConfiguredProductionEmail() {
        var initial = client.authenticationPolicy(env);
        assertEquals(0, initial.passwordMinLength());
        assertFalse(initial.emailActionsAvailable());
        assertStatus(400, () -> client.updateAuthenticationPolicy(env,
            new ProjectController.AuthenticationSettings(true, true, true, 12, initial.revision())));
        realmSettings.put("smtpServer", Map.of("host", "mail.example.invalid", "from", "test@example.invalid", "password", "mail-secret"));
        var ready = client.authenticationPolicy(env);
        assertTrue(ready.emailActionsAvailable());
        assertFalse(json.writeValueAsString(ready).contains("mail-secret"));
        assertFalse(client("dev").authenticationPolicy(env).emailActionsAvailable());
        var changed = client.updateAuthenticationPolicy(env,
            new ProjectController.AuthenticationSettings(false, true, true, 16, ready.revision()));
        assertTrue(changed.verifyEmail());
        assertTrue(changed.resetPasswordAllowed());
        assertFalse(changed.loginWithEmail());
        assertEquals(16, changed.passwordMinLength());
        assertNotEquals(ready.revision(), changed.revision());
        assertEquals(false, realmSettings.get("enabled"));
        assertEquals(true, realmSettings.get("registrationAllowed"));
        assertEquals("length(16) and maxLength(128)", realmSettings.get("passwordPolicy"));
        assertStatus(409, () -> client.updateAuthenticationPolicy(env,
            new ProjectController.AuthenticationSettings(true, false, false, 12, ready.revision())));
    }

    @Test void customPasswordRulesAndDuplicateEmailSettingsAreNotSilentlyOverwritten() {
        realmSettings.put("passwordPolicy", "length(24) and digits(1)");
        assertFalse(client.authenticationPolicy(env).passwordPolicyEditable());
        assertStatus(409, () -> client.updateAuthenticationPolicy(env,
            new ProjectController.AuthenticationSettings(true, false, false, 12, "unconfigured")));
        assertEquals("length(24) and digits(1)", realmSettings.get("passwordPolicy"));
        realmSettings.put("passwordPolicy", AuthenticationPolicy.DEFAULT_PASSWORD_POLICY);
        realmSettings.put("duplicateEmailsAllowed", true);
        assertStatus(409, () -> client.updateAuthenticationPolicy(env,
            new ProjectController.AuthenticationSettings(true, false, false, 12, "unconfigured")));
        assertEquals(-1, AuthenticationPolicy.minimum("length(12) and regexPattern(secret-and-rule)"));
        assertEquals(12, AuthenticationPolicy.minimum(AuthenticationPolicy.DEFAULT_PASSWORD_POLICY));
    }

    private static void assertStatus(int expected, Runnable action) {
        assertEquals(expected, assertThrows(ResponseStatusException.class, action::run).getStatusCode().value());
    }
}
