package kr.shnea.platform.identity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import com.fasterxml.jackson.databind.JsonNode;
import org.keycloak.broker.oidc.AbstractOAuth2IdentityProvider;
import org.keycloak.broker.oidc.OAuth2IdentityProviderConfig;
import org.keycloak.broker.provider.BrokeredIdentityContext;
import org.keycloak.broker.provider.IdentityBrokerException;
import org.keycloak.broker.social.SocialIdentityProvider;
import org.keycloak.http.simple.SimpleHttp;
import org.keycloak.http.simple.SimpleHttpResponse;
import org.keycloak.http.simple.SimpleHttpRequest;
import org.keycloak.models.KeycloakSession;

public final class NaverIdentityProvider extends AbstractOAuth2IdentityProvider implements SocialIdentityProvider {
    private static final String PROFILE_URL = "https://openapi.naver.com/v1/nid/me";

    NaverIdentityProvider(KeycloakSession session, OAuth2IdentityProviderConfig config) {
        super(session, config);
        config.setAuthorizationUrl("https://nid.naver.com/oauth2.0/authorize");
        config.setTokenUrl("https://nid.naver.com/oauth2.0/token");
        config.setUserInfoUrl(PROFILE_URL);
    }

    @Override protected String getDefaultScopes() { return ""; }

    @Override public SimpleHttpRequest authenticateTokenRequest(SimpleHttpRequest request) {
        // The inherited callback verifies state before requesting a token. Naver also
        // requires that value in the code exchange; refresh requests have no state.
        String state = session.getContext().getUri().getQueryParameters().getFirst("state");
        if (state != null) request.param("state", state);
        return super.authenticateTokenRequest(request);
    }

    @Override protected BrokeredIdentityContext doGetFederatedIdentity(String accessToken) {
        try (SimpleHttpResponse response = SimpleHttp.create(session).doGet(PROFILE_URL)
                .header("Authorization", "Bearer " + accessToken).header("Accept", "application/json").asResponse()) {
            if (response.getStatus() != 200) throw new IdentityBrokerException("Naver profile unavailable");
            var identity = parseProfile(response.asJson(), getConfig());
            identity.setIdp(this);
            return identity;
        } catch (Exception error) {
            // Provider payloads and request credentials must not appear in error logs.
            throw new IdentityBrokerException("Naver profile unavailable or invalid");
        }
    }

    static BrokeredIdentityContext parseProfile(JsonNode payload, OAuth2IdentityProviderConfig config) {
        if (payload == null || !"00".equals(text(payload, "resultcode")))
            throw new IdentityBrokerException("Naver profile rejected");
        JsonNode profile = payload.path("response");
        String id = text(profile, "id");
        if (id == null || id.length() > 512) throw new IdentityBrokerException("Naver profile ID missing or invalid");
        var identity = new BrokeredIdentityContext(id, config);
        try {
            identity.setUsername("naver-" + HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(id.getBytes(StandardCharsets.UTF_8))));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
        identity.setFirstName(text(profile, "name"));
        identity.setEmail(text(profile, "email"));
        return identity;
    }

    private static String text(JsonNode object, String key) {
        JsonNode value = object.path(key);
        return value.isTextual() && !value.textValue().isBlank() ? value.textValue() : null;
    }
}
