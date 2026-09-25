package kr.shnea.platform.identity;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.core.NewCookie;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriBuilder;
import org.keycloak.broker.provider.AuthenticationRequest;
import org.keycloak.common.util.SecretGenerator;
import org.keycloak.common.util.Time;
import org.keycloak.http.simple.SimpleHttpRequest;
import org.keycloak.models.IdentityProviderModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.urls.UrlType;

/** Routing only: users, native broker state, PKCE and issued tokens remain in their original realm. */
final class SharedSocialCallback {
    static final String MODE = "shared-v1";
    static final int LIFETIME = 300;
    private static final Set<String> PROVIDERS = Set.of("naver", "kakao", "google");
    private static final String PREFIX = "platform-social:";

    static boolean enabled(IdentityProviderModel config) {
        return MODE.equals(config.getConfig().get("platform.callbackMode"));
    }

    static URI base(KeycloakSession session) {
        // FRONTEND uses the configured public hostname, never a client-supplied return URL.
        return session.getContext().getUri(UrlType.FRONTEND).getBaseUri();
    }

    static String callbackUrl(KeycloakSession session, String provider) {
        if (!PROVIDERS.contains(provider)) throw rejected();
        return UriBuilder.fromUri(base(session)).path("social").path(provider).path("callback").build().toString();
    }

    static Response begin(KeycloakSession session, IdentityProviderModel config, String provider,
                          AuthenticationRequest request, Response response) {
        if (!enabled(config)) return response;
        requireOwned(session, request.getRealm(), config, provider);
        String state = request.getState().getEncoded();
        String id = stateKey(state);
        String browser = SecretGenerator.getInstance().generateSecureID();
        var notes = new HashMap<String, String>();
        notes.put("realm", request.getRealm().getId());
        notes.put("provider", provider);
        notes.put("alias", config.getAlias());
        notes.put("revision", config.getConfig().get("platform.revision"));
        notes.put("clientId", config.getConfig().get("clientId"));
        notes.put("credentials", credentials(config));
        notes.put("browser", digest(browser));
        notes.put("expires", Long.toString((long) Time.currentTime() + LIFETIME));
        notes.put("callback", callbackUrl(session, provider));
        session.singleUseObjects().put(PREFIX + id, LIFETIME, notes);
        URI location = UriBuilder.fromUri(response.getLocation())
            .replaceQueryParam("redirect_uri", notes.get("callback")).build();
        return Response.fromResponse(response).location(location)
            .cookie(cookie(session, id, browser, LIFETIME))
            .header("Cache-Control", "no-store").header("Referrer-Policy", "no-referrer").build();
    }

    static Response route(KeycloakSession session, String provider) {
        if (!PROVIDERS.contains(provider)) throw rejected();
        String state = query(session, "state", true, 8192);
        String code = query(session, "code", false, 4096);
        String error = query(session, "error", false, 128);
        if ((code == null) == (error == null)) throw rejected();
        String id = stateKey(state);
        String key = PREFIX + id;
        Map<String, String> notes = session.singleUseObjects().get(key);
        validateBrowser(session, notes, id, provider);
        RealmModel realm = session.realms().getRealm(notes.get("realm"));
        if (realm == null) throw rejected();
        RealmModel previous = session.getContext().getRealm();
        IdentityProviderModel config;
        try {
            session.getContext().setRealm(realm);
            IdentityProviderModel stored = session.identityProviders().getByAlias(notes.get("alias"));
            config = stored == null ? null : new IdentityProviderModel(stored);
            if (config != null) SocialCredentials.apply(config, provider);
        } finally {
            session.getContext().setRealm(previous);
        }
        requireOwned(session, realm, config, provider);
        validateConfig(notes, config);
        if (!callbackUrl(session, provider).equals(notes.get("callback"))) throw rejected();
        // remove is atomic across Keycloak nodes. Validate before consuming to avoid foreign-browser DoS.
        if (session.singleUseObjects().remove(key) == null) throw rejected();
        UriBuilder target = UriBuilder.fromUri(base(session)).path("realms").path(realm.getName())
            .path("broker").path(config.getAlias()).path("endpoint").queryParam("state", state);
        if (code != null) {
            var returned = new HashMap<>(notes);
            returned.put("code", digest(code));
            session.singleUseObjects().put(key + ":returned", LIFETIME, returned);
            target.queryParam("code", code);
        } else {
            // Do not forward untrusted provider error descriptions or arbitrary callback parameters.
            target.queryParam("error", error.equals("access_denied") ? "access_denied" : "temporarily_unavailable");
        }
        var response = Response.seeOther(target.build()).header("Cache-Control", "no-store")
            .header("Referrer-Policy", "no-referrer");
        if (error != null) response.cookie(cookie(session, id, "", 0));
        return response.build();
    }

    static SimpleHttpRequest tokenRequest(KeycloakSession session, IdentityProviderModel config,
                                         String provider, SimpleHttpRequest request) {
        if (!enabled(config) || !"authorization_code".equals(request.getParam("grant_type"))) return request;
        RealmModel realm = session.getContext().getRealm();
        requireOwned(session, realm, config, provider);
        String state = query(session, "state", true, 8192);
        String id = stateKey(state);
        String key = PREFIX + id + ":returned";
        Map<String, String> notes = session.singleUseObjects().get(key);
        validateBrowser(session, notes, id, provider);
        validateConfig(notes, config);
        if (!realm.getId().equals(notes.get("realm")) || !config.getAlias().equals(notes.get("alias"))
                || request.getParam("code") == null || !digest(request.getParam("code")).equals(notes.get("code"))
                || !callbackUrl(session, provider).equals(notes.get("callback"))) throw rejected();
        if (session.singleUseObjects().remove(key) == null) throw rejected();
        session.getContext().getHttpResponse().setCookieIfAbsent(cookie(session, id, "", 0));
        // Called by the native endpoint AFTER its state/authentication-session validation.
        // SimpleHttpRequest.param replaces the old value, so only one redirect_uri is sent.
        return request.param("redirect_uri", notes.get("callback"));
    }

    private static void requireOwned(KeycloakSession session, RealmModel realm,
                                     IdentityProviderModel config, String provider) {
        if (!"prod".equals(System.getenv().getOrDefault("PLATFORM_MODE", "prod"))
                || realm == null || !realm.isEnabled() || config == null || !config.isEnabled() || !enabled(config)
                || !PROVIDERS.contains(provider) || !("platform-" + provider).equals(config.getAlias())
                || !("platform-" + provider).equals(config.getProviderId())
                || !"PROD".equals(config.getConfig().get("platform.environmentKind"))) throw rejected();
        String environment = realm.getAttribute("platform.environmentId");
        if (environment == null || !environment.equals(config.getConfig().get("platform.environmentId"))
                || !realm.getName().equals("p-" + environment.replace("-", ""))
                || config.getConfig().get("platform.revision") == null
                || config.getConfig().getOrDefault("clientId", "").isBlank()
                || config.getConfig().getOrDefault("clientSecret", "").isBlank()) throw rejected();
    }

    private static void validateConfig(Map<String, String> notes, IdentityProviderModel config) {
        if (!notes.get("revision").equals(config.getConfig().get("platform.revision"))
                || !notes.get("clientId").equals(config.getConfig().get("clientId"))
                || !notes.get("credentials").equals(credentials(config))) throw rejected();
    }

    private static String credentials(IdentityProviderModel config) {
        return digest(config.getConfig().get("clientId") + "\u0000" + config.getConfig().get("clientSecret"));
    }

    private static void validateBrowser(KeycloakSession session, Map<String, String> notes, String id, String provider) {
        if (notes == null || !provider.equals(notes.get("provider"))
                || Long.parseLong(notes.get("expires")) <= Time.currentTime()) throw rejected();
        var browser = session.getContext().getRequestHeaders().getCookies().get(cookieName(id));
        if (browser == null || !MessageDigest.isEqual(digest(browser.getValue()).getBytes(StandardCharsets.US_ASCII),
                notes.get("browser").getBytes(StandardCharsets.US_ASCII))) throw rejected();
    }

    private static NewCookie cookie(KeycloakSession session, String id, String value, int age) {
        return new NewCookie.Builder(cookieName(id)).value(value).path(base(session).getPath())
            .httpOnly(true).secure("https".equals(base(session).getScheme()))
            .sameSite(NewCookie.SameSite.LAX).maxAge(age).build();
    }

    static String cookieName(String id) { return "PLATFORM_SOCIAL_" + id.substring(0, 32); }

    static String query(KeycloakSession session, String name, boolean required, int limit) {
        var values = session.getContext().getUri().getQueryParameters().get(name);
        if (values == null) {
            if (required) throw rejected();
            return null;
        }
        if (values.size() != 1 || values.getFirst().isBlank() || values.getFirst().length() > limit) throw rejected();
        return values.getFirst();
    }

    static String stateKey(String state) {
        if (state == null || state.isBlank() || state.length() > 8192) throw rejected();
        return digest(state);
    }

    static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    static BadRequestException rejected() {
        return new BadRequestException(Response.status(400).type("text/plain; charset=utf-8")
            .header("Cache-Control", "no-store").header("Referrer-Policy", "no-referrer")
            .entity("로그인 요청이 유효하지 않거나 만료되었습니다. 원래 서비스에서 다시 로그인하세요.").build());
    }
}
