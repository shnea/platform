package kr.shnea.platform.identity;

import org.keycloak.broker.oidc.OAuth2IdentityProviderConfig;
import org.keycloak.broker.provider.AbstractIdentityProviderFactory;
import org.keycloak.broker.social.SocialIdentityProviderFactory;
import org.keycloak.models.IdentityProviderModel;
import org.keycloak.models.KeycloakSession;

public final class NaverIdentityProviderFactory extends AbstractIdentityProviderFactory<NaverIdentityProvider>
        implements SocialIdentityProviderFactory<NaverIdentityProvider> {
    @Override public String getName() { return "Naver"; }
    @Override public String getId() { return "platform-naver"; }
    @Override public OAuth2IdentityProviderConfig createConfig() { return new OAuth2IdentityProviderConfig(); }
    @Override public NaverIdentityProvider create(KeycloakSession session, IdentityProviderModel model) {
        var config = new OAuth2IdentityProviderConfig(model);
        SocialCredentials.apply(config, "naver");
        return new NaverIdentityProvider(session, config);
    }
}
