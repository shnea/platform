package kr.shnea.platform.identity;

import org.junit.jupiter.api.Test;
import org.keycloak.broker.oidc.OAuth2IdentityProviderConfig;
import org.keycloak.broker.oidc.OIDCIdentityProviderConfig;
import org.keycloak.broker.provider.BrokeredIdentityContext;
import org.keycloak.models.KeycloakSession;
import org.keycloak.social.google.GoogleIdentityProviderConfig;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class SocialNicknameTest {
    @Test void everyProviderRequiresNicknameWithoutChangingIdentityOrEmail() {
        var session = mock(KeycloakSession.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        org.mockito.Mockito.when(session.getContext().getAuthenticationSession()
            .getClientNote("BROKER_NONCE")).thenReturn("test-nonce");
        var providers = java.util.List.of(
            new GoogleIdentityProviderFactory.Provider(session, new GoogleIdentityProviderConfig()),
            new KakaoIdentityProviderFactory.Provider(session, new OIDCIdentityProviderConfig()),
            new NaverIdentityProvider(session, new OAuth2IdentityProviderConfig()));
        for (var provider : providers) {
            provider.getConfig().setEnabled(true);
            var context = new BrokeredIdentityContext("stable-id", provider.getConfig());
            context.setUsername("opaque-username");
            context.setEmail("member@example.invalid");
            context.setFirstName("Provider real name");
            context.setLastName("Provider surname");
            context.getContextData().put("BROKER_NONCE", "test-nonce");
            provider.preprocessFederatedIdentity(session, null, context);
            assertNull(context.getFirstName());
            assertNull(context.getLastName());
            assertEquals("stable-id", context.getId());
            assertEquals("opaque-username", context.getUsername());
            assertEquals("member@example.invalid", context.getEmail());
            if (provider instanceof org.keycloak.broker.oidc.OIDCIdentityProvider) {
                context.setFirstName("Unverified name");
                context.getContextData().put("BROKER_NONCE", "wrong-nonce");
                assertThrows(org.keycloak.services.ErrorResponseException.class,
                    () -> provider.preprocessFederatedIdentity(session, null, context));
                assertEquals("Unverified name", context.getFirstName());
            }
        }
    }
}
