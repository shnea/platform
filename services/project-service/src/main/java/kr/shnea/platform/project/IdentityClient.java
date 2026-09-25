package kr.shnea.platform.project;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

@Component
class IdentityClient {
    private final RestClient http;
    private final String secret;
    private final String mode;
    private final String publicUrl;

    IdentityClient(@Value("${platform.identity.internal-url}") String internalUrl,
                   @Value("${platform.identity.public-url}") String publicUrl,
                   @Value("${platform.identity.secret}") String secret,
                   @Value("${platform.mode}") String mode) {
        if (!List.of("dev", "prod").contains(mode)) throw new IllegalArgumentException("Invalid platform mode");
        this.mode = mode;
        this.secret = secret;
        this.publicUrl = publicUrl;
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build());
        factory.setReadTimeout(Duration.ofSeconds(10));
        http = RestClient.builder().baseUrl(internalUrl).requestFactory(factory).build();
    }

    String issuer(String realm) { return publicUrl + "/realms/" + realm; }

    @SuppressWarnings("unchecked")
    Map<String, Object> mockLogin(ProjectService.Environment env, String provider, String subject) {
        if (!mode.equals("dev") || !env.kind().equals("DEV")) throw new IllegalStateException("Mock unavailable");
        String admin = accessToken();
        String path = "/admin/realms/" + env.realm();
        List<Map<String, Object>> clients = http.get().uri(path + "/clients?clientId=platform-mock")
            .headers(h -> h.setBearerAuth(admin)).retrieve().body(List.class);
        if (clients.isEmpty()) {
            http.post().uri(path + "/clients").headers(h -> h.setBearerAuth(admin))
                .body(Map.of("clientId", "platform-mock", "publicClient", false,
                    "standardFlowEnabled", false, "directAccessGrantsEnabled", true,
                    "attributes", Map.of("platform.mock", "true")))
                .retrieve().toBodilessEntity();
            clients = http.get().uri(path + "/clients?clientId=platform-mock")
                .headers(h -> h.setBearerAuth(admin)).retrieve().body(List.class);
        }
        Map<String, Object> client = clients.getFirst();
        if (!(client.get("attributes") instanceof Map<?, ?> attrs) || !"true".equals(attrs.get("platform.mock")))
            throw new IllegalStateException("Mock client ownership mismatch");
        Map<String, Object> credentials = http.get().uri(path + "/clients/" + client.get("id") + "/client-secret")
            .headers(h -> h.setBearerAuth(admin)).retrieve().body(Map.class);
        configureMockUserAttribute(path, admin);
        String username = "mock-" + ProjectService.hash(provider + ":" + subject);
        List<Map<String, Object>> users = http.get().uri(path + "/users?exact=true&username=" + username)
            .headers(h -> h.setBearerAuth(admin)).retrieve().body(List.class);
        if (users.isEmpty()) {
            http.post().uri(path + "/users").headers(h -> h.setBearerAuth(admin))
                .body(Map.of("username", username, "enabled", true, "firstName", "Mock", "lastName", provider,
                    "email", username.substring(5) + "@example.invalid", "emailVerified", true,
                    "attributes", Map.of("platformMock", List.of("true"))))
                .retrieve().toBodilessEntity();
            users = http.get().uri(path + "/users?exact=true&username=" + username)
                .headers(h -> h.setBearerAuth(admin)).retrieve().body(List.class);
        }
        Map<String, Object> user = users.getFirst();
        if (!(user.get("attributes") instanceof Map<?, ?> userAttrs)
                || !List.of("true").equals(userAttrs.get("platformMock")))
            throw new IllegalStateException("Mock user ownership mismatch");
        String password = UUID.randomUUID() + "-" + UUID.randomUUID();
        http.put().uri(path + "/users/" + user.get("id") + "/reset-password")
            .headers(h -> h.setBearerAuth(admin))
            .body(Map.of("type", "password", "value", password, "temporary", false))
            .retrieve().toBodilessEntity();
        var form = new LinkedMultiValueMap<String, String>();
        form.add("grant_type", "password");
        form.add("client_id", "platform-mock");
        form.add("client_secret", (String) credentials.get("value"));
        form.add("username", username);
        form.add("password", password);
        Map<String, Object> result = http.post().uri("/realms/" + env.realm() + "/protocol/openid-connect/token")
            .contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form).retrieve().body(Map.class);
        return Map.of("accessToken", result.get("access_token"), "expiresIn", result.get("expires_in"),
            "tokenType", "Bearer", "mode", "mock", "provider", provider,
            "projectId", env.projectId(), "environmentId", env.id(), "issuer", issuer(env.realm()));
    }

    @SuppressWarnings("unchecked")
    private void configureMockUserAttribute(String path, String admin) {
        Map<String, Object> profile = http.get().uri(path + "/users/profile")
            .headers(h -> h.setBearerAuth(admin)).retrieve().body(Map.class);
        var attributes = new ArrayList<>((List<Map<String, Object>>) profile.get("attributes"));
        if (attributes.stream().noneMatch(attribute -> "platformMock".equals(attribute.get("name")))) {
            attributes.add(Map.of("name", "platformMock", "displayName", "Platform mock account",
                "permissions", Map.of("view", List.of("admin"), "edit", List.of("admin"))));
            profile.put("attributes", attributes);
            http.put().uri(path + "/users/profile").headers(h -> h.setBearerAuth(admin))
                .body(profile).retrieve().toBodilessEntity();
        }
    }

    @SuppressWarnings("unchecked")
    private String accessToken() {
        var form = new LinkedMultiValueMap<String, String>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", "platform-provisioner-" + mode);
        form.add("client_secret", secret);
        Map<String, Object> token = http.post().uri("/realms/master/protocol/openid-connect/token")
            .contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form).retrieve().body(Map.class);
        return (String) token.get("access_token");
    }

    void ensureRealm(ProjectService.Environment env) {
        String bearer = accessToken();
        Map<String, Object> existing = readRealm(env.realm(), bearer);
        if (existing == null) {
            var client = Map.of("clientId", "app", "protocol", "openid-connect", "publicClient", true,
                "standardFlowEnabled", true, "directAccessGrantsEnabled", false,
                "redirectUris", env.redirectUris(), "webOrigins", List.of(),
                "attributes", Map.of("pkce.code.challenge.method", "S256"));
            var realm = Map.of("realm", env.realm(), "enabled", true,
                "registrationAllowed", env.registrationAllowed(), "bruteForceProtected", true,
                "sslRequired", env.kind().equals("PROD") ? "all" : "external",
                "accessTokenLifespan", 300, "clients", List.of(client),
                "attributes", Map.of("platform.environmentId", env.id().toString()));
            try {
                http.post().uri("/admin/realms").headers(h -> h.setBearerAuth(bearer))
                    .body(realm).retrieve().toBodilessEntity();
            } catch (RestClientResponseException error) {
                if (error.getStatusCode().value() != 409) throw error;
            }
            // Creation grants new realm roles; fetch a token containing these roles.
            existing = readRealm(env.realm(), accessToken());
        }
        if (existing == null || !(existing.get("attributes") instanceof Map<?, ?> attrs)
                || !env.id().toString().equals(attrs.get("platform.environmentId"))) {
            throw new IllegalStateException("Realm ownership mismatch");
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readRealm(String realm, String token) {
        try {
            return http.get().uri("/admin/realms/{realm}", realm).headers(h -> h.setBearerAuth(token))
                .retrieve().body(Map.class);
        } catch (RestClientResponseException error) {
            if (error.getStatusCode().value() == 404) return null;
            // Before creation, the restricted provisioner may not have permission to view this name.
            if (error.getStatusCode().value() == 403) return null;
            throw error;
        }
    }
}
