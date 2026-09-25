package kr.shnea.platform.identity;

import jakarta.ws.rs.core.Response;
import org.keycloak.broker.oidc.OIDCIdentityProvider;
import org.keycloak.broker.oidc.OIDCIdentityProviderConfig;
import org.keycloak.broker.provider.AbstractIdentityProviderFactory;
import org.keycloak.broker.provider.AuthenticationRequest;
import org.keycloak.broker.social.SocialIdentityProviderFactory;
import org.keycloak.broker.social.SocialIdentityProvider;
import org.keycloak.http.simple.SimpleHttpRequest;
import org.keycloak.models.IdentityProviderModel;
import org.keycloak.models.KeycloakSession;

public final class KakaoIdentityProviderFactory extends AbstractIdentityProviderFactory<KakaoIdentityProviderFactory.Provider>
        implements SocialIdentityProviderFactory<KakaoIdentityProviderFactory.Provider> {
    @Override public String getName() { return "Platform Kakao"; }
    @Override public String getId() { return "platform-kakao"; }
    @Override public OIDCIdentityProviderConfig createConfig() { return new OIDCIdentityProviderConfig(); }
    @Override public Provider create(KeycloakSession session, IdentityProviderModel model) {
        var config = new OIDCIdentityProviderConfig(model);
        SocialCredentials.apply(config, "kakao");
        return new Provider(session, config);
    }
    public static final class Provider extends OIDCIdentityProvider implements SocialIdentityProvider<OIDCIdentityProviderConfig> {
        Provider(KeycloakSession session, OIDCIdentityProviderConfig config) { super(session, config); }
        @Override public Response performLogin(AuthenticationRequest request) {
            return SharedSocialCallback.begin(session, getConfig(), "kakao", request, super.performLogin(request));
        }
        @Override public SimpleHttpRequest authenticateTokenRequest(SimpleHttpRequest request) {
            return super.authenticateTokenRequest(SharedSocialCallback.tokenRequest(session, getConfig(), "kakao", request));
        }
    }
}
