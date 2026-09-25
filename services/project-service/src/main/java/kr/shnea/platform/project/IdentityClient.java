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
    private final EmailClient emails;

    IdentityClient(@Value("${platform.identity.internal-url}") String internalUrl,
                   @Value("${platform.identity.public-url}") String publicUrl,
                   @Value("${platform.identity.secret}") String secret,
                   @Value("${platform.mode}") String mode, EmailClient emails) {
        if (!List.of("dev", "prod").contains(mode)) throw new IllegalArgumentException("Invalid platform mode");
        this.mode = mode;
        this.secret = secret;
        this.publicUrl = publicUrl;
        this.emails = emails;
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build());
        factory.setReadTimeout(Duration.ofSeconds(10));
        http = RestClient.builder().baseUrl(internalUrl).requestFactory(factory).build();
    }

    String issuer(String realm) { return publicUrl + "/realms/" + realm; }

    @SuppressWarnings("unchecked")
    Member.Page members(ProjectService.Environment env, String search, int limit, int offset) {
        String admin = ownedRealmToken(env);
        List<Map<String, Object>> rows = http.get().uri(builder -> builder
            .path("/admin/realms/{realm}/users").queryParam("search", "{search}")
            .queryParam("first", offset).queryParam("max", limit + 1).queryParam("briefRepresentation", false)
            .build(env.realm(), search.isEmpty() ? "" : "*" + search + "*")).headers(h -> h.setBearerAuth(admin)).retrieve().body(List.class);
        return new Member.Page(rows.stream().limit(limit).map(Member::from).toList(), rows.size() > limit);
    }

    @SuppressWarnings("unchecked")
    Member.Detail member(ProjectService.Environment env, UUID userId) {
        String admin = ownedRealmToken(env);
        var user = readMember(env, userId, admin);
        List<Map<String, Object>> links = http.get().uri(memberPath(env, userId) + "/federated-identity")
            .headers(h -> h.setBearerAuth(admin)).retrieve().body(List.class);
        return new Member.Detail(Member.from(user), links.stream().map(link -> (String) link.get("identityProvider")).toList());
    }

    List<Member.Session> memberSessions(ProjectService.Environment env, UUID userId) {
        String admin = ownedRealmToken(env);
        readMember(env, userId, admin);
        return readSessions(env, userId, admin);
    }

    void updateMember(ProjectService.Environment env, UUID userId, boolean enabled, boolean expectedEnabled) {
        String admin = ownedRealmToken(env);
        var current = readMember(env, userId, admin);
        if (Boolean.TRUE.equals(current.get("enabled")) != expectedEnabled)
            throw ApiCode.MEMBER_STATE_CHANGED.failure();
        http.put().uri(memberPath(env, userId)).headers(h -> h.setBearerAuth(admin))
            .body(Map.of("enabled", enabled)).retrieve().toBodilessEntity();
        // Disable first: logout failure must never re-enable the account. Safe to retry.
        if (!enabled) logoutMember(env, userId, admin);
    }

    void endMemberSessions(ProjectService.Environment env, UUID userId, String sessionId) {
        String admin = ownedRealmToken(env);
        readMember(env, userId, admin);
        if (sessionId == null) {
            logoutMember(env, userId, admin);
        } else {
            if (readSessions(env, userId, admin).stream().noneMatch(session -> sessionId.equals(session.id())))
                throw ApiCode.RESOURCE_NOT_FOUND.failure();
            try {
                http.delete().uri("/admin/realms/" + env.realm() + "/sessions/" + sessionId + "?isOffline=false")
                    .headers(h -> h.setBearerAuth(admin)).retrieve().toBodilessEntity();
            } catch (RestClientResponseException error) {
                // A session can expire between ownership verification and deletion.
                if (error.getStatusCode().value() != 404) throw error;
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void logoutMember(ProjectService.Environment env, UUID userId, String admin) {
        http.post().uri(memberPath(env, userId) + "/logout").headers(h -> h.setBearerAuth(admin))
            .retrieve().toBodilessEntity();
        // Keycloak logout only removes online sessions. Discover this user's offline grants
        // and delete their sessions explicitly, preserving the user's consent settings.
        List<Map<String, Object>> consents = http.get().uri(memberPath(env, userId) + "/consents")
            .headers(h -> h.setBearerAuth(admin)).retrieve().body(List.class);
        var offlineIds = new java.util.HashSet<String>();
        for (var consent : consents) {
            var grants = (List<Map<String, String>>) consent.getOrDefault("additionalGrants", List.of());
            for (var grant : grants) {
                if (!"Offline Token".equals(grant.get("key"))) continue;
                List<Map<String, Object>> sessions = http.get()
                    .uri(memberPath(env, userId) + "/offline-sessions/{clientId}", grant.get("client"))
                    .headers(h -> h.setBearerAuth(admin)).retrieve().body(List.class);
                sessions.forEach(session -> offlineIds.add((String) session.get("id")));
            }
        }
        for (String sessionId : offlineIds) {
            try {
                http.delete().uri("/admin/realms/{realm}/sessions/{session}?isOffline=true", env.realm(), sessionId)
                    .headers(h -> h.setBearerAuth(admin)).retrieve().toBodilessEntity();
            } catch (RestClientResponseException error) {
                if (error.getStatusCode().value() != 404) throw error;
            }
        }
    }

    private String memberPath(ProjectService.Environment env, UUID userId) {
        return "/admin/realms/" + env.realm() + "/users/" + userId;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readMember(ProjectService.Environment env, UUID userId, String admin) {
        try {
            Map<String, Object> user = http.get().uri(memberPath(env, userId))
                .headers(h -> h.setBearerAuth(admin)).retrieve().body(Map.class);
            if (user == null || user.get("serviceAccountClientId") != null)
                throw ApiCode.RESOURCE_NOT_FOUND.failure();
            // GET /users/{id} omits the service-account link in Keycloak 26.7.
            // General search excludes service accounts; exact username lookup does not.
            boolean member = false;
            for (int offset = 0; !member; offset += 100) {
                final int first = offset;
                List<Map<String, Object>> matches = http.get().uri(builder -> builder
                    .path("/admin/realms/{realm}/users").queryParam("search", "{search}")
                    .queryParam("exact", true).queryParam("first", first).queryParam("max", 100)
                    .build(env.realm(), "\"" + user.get("username") + "\""))
                    .headers(h -> h.setBearerAuth(admin)).retrieve().body(List.class);
                member = matches.stream().anyMatch(row -> userId.toString().equals(row.get("id")));
                if (!member && matches.size() < 100)
                    throw ApiCode.RESOURCE_NOT_FOUND.failure();
            }
            return user;
        } catch (RestClientResponseException error) {
            if (error.getStatusCode().value() == 404)
                throw ApiCode.RESOURCE_NOT_FOUND.failure();
            throw error;
        }
    }

    @SuppressWarnings("unchecked")
    private List<Member.Session> readSessions(ProjectService.Environment env, UUID userId, String admin) {
        List<Map<String, Object>> sessions = http.get().uri(memberPath(env, userId) + "/sessions")
            .headers(h -> h.setBearerAuth(admin)).retrieve().body(List.class);
        return sessions.stream().map(session -> new Member.Session((String) session.get("id"),
            (String) session.get("ipAddress"), ((Number) session.get("start")).longValue(),
            ((Number) session.get("lastAccess")).longValue(),
            List.copyOf(((Map<String, String>) session.get("clients")).values()))).toList();
    }

    AuthenticationPolicy authenticationPolicy(ProjectService.Environment env) {
        String admin = ownedRealmToken(env);
        return policyMetadata(env, readRealm(env.realm(), admin));
    }

    AuthenticationPolicy updateAuthenticationPolicy(ProjectService.Environment env, ProjectController.AuthenticationSettings request) {
        String admin = ownedRealmToken(env);
        Map<String, Object> realm = readRealm(env.realm(), admin);
        var current = policyMetadata(env, realm);
        if (!current.revision().equals(request.revision()))
            throw ApiCode.SETTINGS_CHANGED.failure();
        if (!current.passwordPolicyEditable())
            throw ApiCode.AUTHENTICATION_POLICY_REVIEW.failure();
        if ((request.verifyEmail() || request.resetPasswordAllowed()) && !current.emailActionsAvailable())
            throw ApiCode.EMAIL_DELIVERY_NOT_READY.failure();
        if (request.loginWithEmail() && Boolean.TRUE.equals(realm.get("duplicateEmailsAllowed")))
            throw ApiCode.AUTHENTICATION_POLICY_REVIEW.failure();
        @SuppressWarnings("unchecked")
        var attributes = new java.util.HashMap<String, Object>((Map<String, Object>) realm.get("attributes"));
        attributes.put("platform.authPolicyRevision", UUID.randomUUID().toString());
        http.put().uri("/admin/realms/" + env.realm()).headers(h -> h.setBearerAuth(admin))
            .body(Map.of("loginWithEmailAllowed", request.loginWithEmail(), "verifyEmail", request.verifyEmail(),
                "resetPasswordAllowed", request.resetPasswordAllowed(),
                "passwordPolicy", "length(" + request.passwordMinLength() + ") and maxLength(128)",
                "attributes", attributes))
            .retrieve().toBodilessEntity();
        return policyMetadata(env, readRealm(env.realm(), admin));
    }

    private AuthenticationPolicy policyMetadata(ProjectService.Environment env, Map<String, Object> realm) {
        if (realm == null || !(realm.get("attributes") instanceof Map<?, ?> attrs)
                || !env.id().toString().equals(attrs.get("platform.environmentId")))
            throw ApiCode.REALM_OWNERSHIP_MISMATCH.failure();
        int minimum = AuthenticationPolicy.minimum((String) realm.get("passwordPolicy"));
        String delivery = emails.delivery(env);
        return new AuthenticationPolicy(!Boolean.FALSE.equals(realm.get("loginWithEmailAllowed")),
            Boolean.TRUE.equals(realm.get("verifyEmail")), Boolean.TRUE.equals(realm.get("resetPasswordAllowed")),
            minimum, minimum >= 0 && minimum <= 128, !delivery.equals("UNAVAILABLE"), delivery,
            attrs.get("platform.authPolicyRevision") instanceof String revision ? revision : "unconfigured");
    }

    List<SocialProvider.Metadata> socialProviders(ProjectService.Environment env) {
        String admin = ownedRealmToken(env);
        var readiness = socialReadiness();
        return SocialProvider.ALL.stream().map(provider -> socialMetadata(env, provider, readSocial(env, provider, admin), readiness)).toList();
    }

    SocialProvider.Metadata updateSocialProvider(ProjectService.Environment env, SocialProvider provider,
                                                 ProjectController.SocialSettings request) {
        if (request.enabled() && !activationAllowed(env))
            throw ApiCode.SOCIAL_PRODUCTION_REQUIRED.failure();
        String admin = ownedRealmToken(env);
        Map<String, Object> current = readSocial(env, provider, admin);
        var readiness = socialReadiness();
        var metadata = socialMetadata(env, provider, current, readiness);
        if (!metadata.revision().equals(request.revision()))
            throw ApiCode.SETTINGS_CHANGED.failure();
        if (request.enabled() && !metadata.credentialsConfigured())
            throw ApiCode.SOCIAL_CREDENTIALS_MISSING.failure();
        var config = provider.config();
        config.put("platform.environmentId", env.id().toString());
        config.put("platform.environmentKind", env.kind());
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
                    throw ApiCode.SOCIAL_PROVIDER_CONFLICT.failure();
                throw error;
            }
        } else {
            representation.put("internalId", current.get("internalId"));
            http.put().uri(path + "/" + provider.alias()).headers(h -> h.setBearerAuth(admin))
                .body(representation).retrieve().toBodilessEntity();
        }
        return socialMetadata(env, provider, readSocial(env, provider, admin), readiness);
    }

    private boolean activationAllowed(ProjectService.Environment env) {
        return mode.equals("prod") && env.kind().equals("PROD");
    }

    private String ownedRealmToken(ProjectService.Environment env) {
        String admin = accessToken();
        Map<String, Object> realm = readRealm(env.realm(), admin);
        if (realm == null || !(realm.get("attributes") instanceof Map<?, ?> attrs)
                || !env.id().toString().equals(attrs.get("platform.environmentId")))
            throw ApiCode.REALM_OWNERSHIP_MISMATCH.failure();
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
                    || !provider.acceptsProviderId(result.get("providerId"))
                    || !(config.get("platform.revision") instanceof String revision) || revision.isBlank())
                throw ApiCode.SOCIAL_PROVIDER_CONFLICT.failure();
            return result;
        } catch (RestClientResponseException error) {
            if (error.getStatusCode().value() == 404) return null;
            throw error;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Boolean> socialReadiness() {
        return http.get().uri("/realms/master/platform-social/configuration").retrieve().body(Map.class);
    }

    private SocialProvider.Metadata socialMetadata(ProjectService.Environment env, SocialProvider provider, Map<String, Object> data,
                                                    Map<String, Boolean> readiness) {
        Map<?, ?> config = data == null ? Map.of() : (Map<?, ?>) data.get("config");
        boolean shared = data == null || "shared-v1".equals(config.get("platform.callbackMode"));
        return new SocialProvider.Metadata(provider.code(), provider.label(), provider.alias(), data != null,
            data != null && Boolean.TRUE.equals(data.get("enabled")),
            readiness != null && Boolean.TRUE.equals(readiness.get(provider.code())),
            config.get("platform.revision") instanceof String revision ? revision : "unconfigured",
            shared ? publicUrl + "/social/" + provider.code() + "/callback"
                : issuer(env.realm()) + "/broker/" + provider.alias() + "/endpoint", activationAllowed(env), shared,
            publicUrl + "/social/" + provider.code() + "/callback");
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> mockLogin(ProjectService.Environment env, String provider, String subject) {
        if (!mode.equals("dev") || !env.kind().equals("DEV")) throw new IllegalStateException("Mock unavailable");
        String admin = ownedRealmToken(env);
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
        if (!Boolean.TRUE.equals(user.get("enabled")))
            throw ApiCode.MOCK_USER_DISABLED.failure();
        // Meet every managed minimum (12..128) without exceeding maxLength(128).
        String password = ProjectService.hash(UUID.randomUUID().toString()) + ProjectService.hash(UUID.randomUUID().toString());
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

    private String mockResetToken(ProjectService.Environment env) {
        if (!mode.equals("dev") || !env.kind().equals("DEV")
                || !env.realm().equals("p-" + env.id().toString().replace("-", "")))
            throw ApiCode.DEV_ENVIRONMENT_REQUIRED.failure();
        String admin = ownedRealmToken(env);
        // A user-editable marker is not evidence of a disposable account.
        Map<?, ?> profile = http.get().uri("/admin/realms/{realm}/users/profile", env.realm())
            .headers(h -> h.setBearerAuth(admin)).retrieve().body(Map.class);
        if (!(profile.get("attributes") instanceof List<?> attributes))
            throw ApiCode.MOCK_RESET_PROTECTION.failure();
        for (Object entry : attributes) {
            if (entry instanceof Map<?, ?> attribute && "platformMock".equals(attribute.get("name"))) {
                if (!(attribute.get("permissions") instanceof Map<?, ?> permissions)
                        || !List.of("admin").equals(permissions.get("edit")))
                    throw ApiCode.MOCK_RESET_PROTECTION.failure();
                return admin;
            }
        }
        return null; // A fresh realm has no mock marker and therefore no resettable users.
    }

    @SuppressWarnings("unchecked")
    MockReset.Preview mockResetPreview(ProjectService.Environment env) {
        String admin = mockResetToken(env);
        var targets = new ArrayList<MockReset.Target>();
        if (admin == null) return mockPreview(env, targets, false);
        // Bound one synchronous operation; a large fixture set is reset in reviewed batches.
        for (int offset = 0; offset < 1000; offset += 100) {
            List<Map<String, Object>> rows = http.get()
                .uri("/admin/realms/{realm}/users?q=platformMock:true&first={first}&max=100&briefRepresentation=false", env.realm(), offset)
                .headers(h -> h.setBearerAuth(admin)).retrieve().body(List.class);
            for (var row : rows) {
                if (disposableMock(env, row, admin)) {
                    targets.add(new MockReset.Target(UUID.fromString((String) row.get("id")),
                        (String) row.get("username"), (String) row.get("lastName")));
                    if (targets.size() > MockReset.BATCH_SIZE) return mockPreview(env, targets, true);
                }
            }
            if (rows.size() < 100) return mockPreview(env, targets, false);
        }
        throw ApiCode.MOCK_RESET_PROTECTION.failure();
    }

    private MockReset.Preview mockPreview(ProjectService.Environment env, List<MockReset.Target> targets, boolean more) {
        var items = targets.stream().limit(MockReset.BATCH_SIZE)
            .sorted(java.util.Comparator.comparing(target -> target.id().toString())).toList();
        return new MockReset.Preview(items, more, ProjectService.hash(env.id() + ":" + items));
    }

    @SuppressWarnings("unchecked")
    private boolean disposableMock(ProjectService.Environment env, Map<String, Object> user, String admin) {
        if (!(user.get("attributes") instanceof Map<?, ?> attrs)
                || !List.of("true").equals(attrs.get("platformMock"))
                || !(user.get("username") instanceof String name) || !name.matches("mock-[a-f0-9]{64}")
                || user.get("serviceAccountClientId") != null || user.get("federationLink") != null
                || !"Mock".equals(user.get("firstName"))
                || !List.of("google", "kakao", "naver").contains(java.util.Objects.toString(user.get("lastName"), ""))
                || !(name.substring(5) + "@example.invalid").equals(user.get("email"))) return false;
        String path = memberPath(env, UUID.fromString((String) user.get("id")));
        for (String suffix : List.of("/groups?max=1", "/federated-identity")) {
            List<?> links = http.get().uri(path + suffix).headers(h -> h.setBearerAuth(admin)).retrieve().body(List.class);
            if (!links.isEmpty()) return false;
        }
        List<Map<String, Object>> roles = http.get().uri(path + "/role-mappings/realm/composite")
            .headers(h -> h.setBearerAuth(admin)).retrieve().body(List.class);
        var defaults = List.of("default-roles-" + env.realm(), "offline_access", "uma_authorization");
        if (roles.stream().anyMatch(role -> !defaults.contains(role.get("name")))) return false;
        Map<String, Object> mappings = http.get().uri(path + "/role-mappings")
            .headers(h -> h.setBearerAuth(admin)).retrieve().body(Map.class);
        if (mappings.get("clientMappings") instanceof Map<?, ?> clients
                && clients.keySet().stream().anyMatch(client -> !"account".equals(client))) return false;
        // Effective admin roles may also be hidden inside a default/composite role.
        List<Map<String, Object>> management = http.get().uri("/admin/realms/{realm}/clients?clientId=realm-management", env.realm())
            .headers(h -> h.setBearerAuth(admin)).retrieve().body(List.class);
        if (management.size() != 1) return false;
        List<?> adminRoles = http.get().uri(path + "/role-mappings/clients/{client}/composite", management.getFirst().get("id"))
            .headers(h -> h.setBearerAuth(admin)).retrieve().body(List.class);
        return adminRoles.isEmpty();
    }

    void deleteMockUser(ProjectService.Environment env, UUID userId) {
        String admin = mockResetToken(env);
        if (admin == null) throw ApiCode.MOCK_RESET_PROTECTION.failure();
        var user = readMember(env, userId, admin);
        if (!disposableMock(env, user, admin))
            throw ApiCode.MOCK_RESET_CHANGED.failure();
        // Close login before terminating online/offline sessions. Failure stays disabled.
        http.put().uri(memberPath(env, userId)).headers(h -> h.setBearerAuth(admin))
            .body(Map.of("enabled", false)).retrieve().toBodilessEntity();
        logoutMember(env, userId, admin);
        http.delete().uri(memberPath(env, userId)).headers(h -> h.setBearerAuth(admin)).retrieve().toBodilessEntity();
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
                "passwordPolicy", AuthenticationPolicy.DEFAULT_PASSWORD_POLICY,
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
            .body(Map.of("enabled", enabled, "registrationAllowed", env.registrationAllowed(),
                "internationalizationEnabled", true, "supportedLocales", List.of("ko"), "defaultLocale", "ko"))
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
