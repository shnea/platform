package kr.shnea.platform.project;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
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

    List<SocialProvider.Metadata> socialProviders(ProjectService.Environment env) {
        String admin = ownedRealmToken(env);
        return SocialProvider.ALL.stream().map(provider -> socialMetadata(env, provider, readSocial(env, provider, admin))).toList();
    }

    SocialProvider.Metadata updateSocialProvider(ProjectService.Environment env, SocialProvider provider,
                                                 ProjectController.SocialSettings request) {
        if (request.enabled() && !activationAllowed(env))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Real social login requires production mode and a PROD environment");
        String admin = ownedRealmToken(env);
        Map<String, Object> current = readSocial(env, provider, admin);
        var metadata = socialMetadata(env, provider, current);
        if (!metadata.revision().equals(request.revision()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Social settings changed; reload before saving");
        Map<?, ?> previous = current == null ? Map.of() : (Map<?, ?>) current.get("config");
        String clientId = request.clientId().strip();
        String clientSecret = request.clientSecret();
        if (clientSecret == null || clientSecret.isBlank()) {
            if (!metadata.secretConfigured() || !clientId.equals(metadata.clientId()))
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A new client ID requires its client secret");
            // Keycloak returns a masked secret and explicitly preserves it on PUT.
            clientSecret = (String) previous.get("clientSecret");
        }
        var config = provider.config();
        config.put("clientId", clientId);
        config.put("clientSecret", clientSecret);
        config.put("platform.environmentId", env.id().toString());
        config.put("platform.revision", UUID.randomUUID().toString());
        var representation = new java.util.HashMap<String, Object>();
        representation.put("alias", provider.alias());
        representation.put("displayName", provider.label());
        representation.put("providerId", provider.providerId());
        representation.put("enabled", request.enabled());
        representation.put("trustEmail", false);
        representation.put("storeToken", false);
        representation.put("addReadTokenRoleOnCreate", false);
        representation.put("linkOnly", false);
        representation.put("firstBrokerLoginFlowAlias", "first broker login");
        representation.put("config", config);
        String path = socialPath(env);
        if (current == null) {
            try {
                http.post().uri(path).headers(h -> h.setBearerAuth(admin)).body(representation).retrieve().toBodilessEntity();
            } catch (RestClientResponseException error) {
                if (error.getStatusCode().value() == 409)
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "Social provider already exists; reload");
                throw error;
            }
        } else {
            representation.put("internalId", current.get("internalId"));
            http.put().uri(path + "/" + provider.alias()).headers(h -> h.setBearerAuth(admin))
                .body(representation).retrieve().toBodilessEntity();
        }
        return socialMetadata(env, provider, readSocial(env, provider, admin));
    }

    private boolean activationAllowed(ProjectService.Environment env) {
        return mode.equals("prod") && env.kind().equals("PROD");
    }

    private String ownedRealmToken(ProjectService.Environment env) {
        String admin = accessToken();
        Map<String, Object> realm = readRealm(env.realm(), admin);
        if (realm == null || !(realm.get("attributes") instanceof Map<?, ?> attrs)
                || !env.id().toString().equals(attrs.get("platform.environmentId")))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Realm ownership mismatch");
        return admin;
    }

    private String socialPath(ProjectService.Environment env) {
        return "/admin/realms/" + env.realm() + "/identity-provider/instances";
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readSocial(ProjectService.Environment env, SocialProvider provider, String admin) {
        try {
            Map<String, Object> result = http.get().uri(socialPath(env) + "/" + provider.alias())
                .headers(h -> h.setBearerAuth(admin)).retrieve().body(Map.class);
            if (result == null || !(result.get("config") instanceof Map<?, ?> config)
                    || !env.id().toString().equals(config.get("platform.environmentId"))
                    || !provider.providerId().equals(result.get("providerId"))
                    || !(config.get("platform.revision") instanceof String revision) || revision.isBlank())
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Unmanaged social provider; inspect Keycloak settings");
            return result;
        } catch (RestClientResponseException error) {
            if (error.getStatusCode().value() == 404) return null;
            throw error;
        }
    }

    private SocialProvider.Metadata socialMetadata(ProjectService.Environment env, SocialProvider provider, Map<String, Object> data) {
        Map<?, ?> config = data == null ? Map.of() : (Map<?, ?>) data.get("config");
        return new SocialProvider.Metadata(provider.code(), provider.label(), provider.alias(), data != null,
            data != null && Boolean.TRUE.equals(data.get("enabled")),
            config.get("clientId") instanceof String id ? id : "",
            config.get("clientSecret") instanceof String secret && !secret.isBlank(),
            config.get("platform.revision") instanceof String revision ? revision : "unconfigured",
            issuer(env.realm()) + "/broker/" + provider.alias() + "/endpoint", activationAllowed(env));
    }

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
            "projectId", env.projectId(), "environmentId", env.id(), "issuer", issuer(env.realm()), "userId", user.get("id"));
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

    @SuppressWarnings("unchecked")
    void ensureRealm(ProjectService.Environment env, boolean enabled) {
        String bearer = accessToken();
        Map<String, Object> existing = readRealm(env.realm(), bearer);
        if (existing == null) {
            var client = Map.of("clientId", "app", "protocol", "openid-connect", "publicClient", true,
                "standardFlowEnabled", true, "directAccessGrantsEnabled", false,
                "redirectUris", env.redirectUris(), "webOrigins", List.of(),
                "attributes", Map.of("pkce.code.challenge.method", "S256"));
            var realm = Map.of("realm", env.realm(), "enabled", false,
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
        String admin = accessToken();
        String path = "/admin/realms/" + env.realm();
        // Disable first; a later failure must never reopen a suspended project.
        if (!enabled) {
            http.put().uri(path).headers(h -> h.setBearerAuth(admin)).body(Map.of("enabled", false))
                .retrieve().toBodilessEntity();
            http.post().uri(path + "/logout-all").headers(h -> h.setBearerAuth(admin)).retrieve().toBodilessEntity();
        }
        List<Map<String, Object>> clients = http.get().uri(path + "/clients?clientId=app")
            .headers(h -> h.setBearerAuth(admin)).retrieve().body(List.class);
        if (clients == null || clients.size() != 1) throw new IllegalStateException("App client missing; restore before retry");
        Map<String, Object> client = clients.getFirst();
        client.put("redirectUris", env.redirectUris());
        client.put("webOrigins", env.redirectUris().stream().map(java.net.URI::create)
            .map(uri -> uri.getScheme() + "://" + uri.getRawAuthority()).distinct().toList());
        client.put("directAccessGrantsEnabled", false);
        client.put("standardFlowEnabled", true);
        var attributes = new java.util.HashMap<>((Map<String, Object>) client.getOrDefault("attributes", Map.of()));
        attributes.put("pkce.code.challenge.method", "S256");
        client.put("attributes", attributes);
        http.put().uri(path + "/clients/" + client.get("id")).headers(h -> h.setBearerAuth(admin))
            .body(client).retrieve().toBodilessEntity();
        http.put().uri(path).headers(h -> h.setBearerAuth(admin))
            .body(Map.of("enabled", enabled, "registrationAllowed", env.registrationAllowed()))
            .retrieve().toBodilessEntity();
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
