package kr.shnea.platform.identity;

import jakarta.ws.rs.core.Response;
import org.keycloak.broker.provider.AbstractIdentityProviderFactory;
import org.keycloak.broker.provider.AuthenticationRequest;
import org.keycloak.broker.social.SocialIdentityProviderFactory;
import org.keycloak.http.simple.SimpleHttpRequest;
import org.keycloak.models.IdentityProviderModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.social.google.GoogleIdentityProvider;
import org.keycloak.social.google.GoogleIdentityProviderConfig;

public final class GoogleIdentityProviderFactory extends AbstractIdentityProviderFactory<GoogleIdentityProviderFactory.Provider>
        implements SocialIdentityProviderFactory<GoogleIdentityProviderFactory.Provider> {
    @Override public String getName() { return "Platform Google"; }
    @Override public String getId() { return "platform-google"; }
    @Override public GoogleIdentityProviderConfig createConfig() {
        return new org.keycloak.social.google.GoogleIdentityProviderFactory().createConfig();
    }
    @Override public Provider create(KeycloakSession session, IdentityProviderModel model) {
        var config = new GoogleIdentityProviderConfig(model);
        SocialCredentials.apply(config, "google");
        return new Provider(session, config);
    }
    public static final class Provider extends GoogleIdentityProvider {
        Provider(KeycloakSession session, GoogleIdentityProviderConfig config) { super(session, config); }
        @Override public void preprocessFederatedIdentity(KeycloakSession session, org.keycloak.models.RealmModel realm,
                org.keycloak.broker.provider.BrokeredIdentityContext context) {
            super.preprocessFederatedIdentity(session, realm, context);
            SocialNickname.requireUserChoice(context);
        }
        @Override public void updateBrokeredUser(KeycloakSession session, org.keycloak.models.RealmModel realm,
                org.keycloak.models.UserModel user, org.keycloak.broker.provider.BrokeredIdentityContext context) {
            super.updateBrokeredUser(session, realm, user, context);
            SocialEmail.verify(user, context);
        }
        @Override public Response performLogin(AuthenticationRequest request) {
            return SharedSocialCallback.begin(session, getConfig(), "google", request, super.performLogin(request));
        }
        @Override public SimpleHttpRequest authenticateTokenRequest(SimpleHttpRequest request) {
            return super.authenticateTokenRequest(SharedSocialCallback.tokenRequest(session, getConfig(), "google", request));
        }
    }
}
