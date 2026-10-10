package kr.shnea.platform.identity;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.keycloak.authentication.RequiredActionContext;
import org.keycloak.authentication.requiredactions.VerifyEmail;
import org.keycloak.broker.oidc.OAuth2IdentityProviderConfig;
import org.keycloak.broker.oidc.OIDCIdentityProviderConfig;
import org.keycloak.broker.provider.AbstractIdentityProvider;
import org.keycloak.broker.provider.BrokeredIdentityContext;
import org.keycloak.models.IdentityProviderSyncMode;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.sessions.AuthenticationSessionModel;
import org.keycloak.social.google.GoogleIdentityProviderConfig;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SocialEmailTest {
    @Test void everyProviderVerifiesNewAndExistingEmailsEvenWithLegacyTrustDisabled() {
        var session = mock(KeycloakSession.class, RETURNS_DEEP_STUBS);
        when(session.getContext().getAuthenticationSession().getClientNote("BROKER_NONCE")).thenReturn("test-nonce");
        var realm = mock(RealmModel.class);
        when(realm.isVerifyEmail()).thenReturn(true);
        var providers = List.of(
            new GoogleIdentityProviderFactory.Provider(session, new GoogleIdentityProviderConfig()),
            new KakaoIdentityProviderFactory.Provider(session, new OIDCIdentityProviderConfig()),
            new NaverIdentityProvider(session, new OAuth2IdentityProviderConfig()));
        for (var provider : providers) for (boolean newUser : List.of(true, false)) {
            provider.getConfig().setEnabled(true);
            provider.getConfig().setSyncMode(IdentityProviderSyncMode.IMPORT);
            provider.getConfig().setTrustEmail(false);
            var authSession = mock(AuthenticationSessionModel.class);
            when(authSession.getAuthNote(AbstractIdentityProvider.BROKER_REGISTERED_NEW_USER)).thenReturn(Boolean.toString(newUser));
            var context = new BrokeredIdentityContext("stable-id", provider.getConfig());
            context.setAuthenticationSession(authSession);
            context.setEmail("Member@example.invalid");
            context.getContextData().put("BROKER_NONCE", "test-nonce");
            var user = mock(UserModel.class);
            when(user.getEmail()).thenReturn("member@example.invalid");
            var verified = new AtomicBoolean(false);
            when(user.isEmailVerified()).thenAnswer(invocation -> verified.get());
            doAnswer(invocation -> { verified.set(invocation.getArgument(0)); return null; }).when(user).setEmailVerified(anyBoolean());
            var actions = new LinkedHashSet<>(Set.of("VERIFY_EMAIL", "UPDATE_PASSWORD"));
            doAnswer(invocation -> { actions.remove(((UserModel.RequiredAction)invocation.getArgument(0)).name()); return null; })
                .when(user).removeRequiredAction(any(UserModel.RequiredAction.class));
            provider.preprocessFederatedIdentity(session, realm, context);
            provider.updateBrokeredUser(session, realm, user, context);
            assertTrue(verified.get());
            assertEquals(Set.of("UPDATE_PASSWORD"), actions);
            assertEquals("stable-id", context.getId());
            verify(authSession).removeRequiredAction("VERIFY_EMAIL");
            var required = mock(RequiredActionContext.class);
            when(required.getRealm()).thenReturn(realm);
            when(required.getUser()).thenReturn(user);
            when(required.getAuthenticationSession()).thenReturn(authSession);
            new VerifyEmail().evaluateTriggers(required);
            new VerifyEmail().requiredActionChallenge(required);
            verify(user, never()).addRequiredAction(UserModel.RequiredAction.VERIFY_EMAIL);
            verify(required).success();
            verify(required, never()).form();
        }
    }

    @Test void missingProviderEmailDifferentLocalEmailAndProfileChangesAreNotVerified() {
        for (String email : List.of("", " ", "other@example.invalid")) {
            var config = new OAuth2IdentityProviderConfig(); config.setEnabled(true);
            var context = new BrokeredIdentityContext("stable-id", config);
            context.setAuthenticationSession(mock(AuthenticationSessionModel.class));
            context.setEmail(email);
            var user = mock(UserModel.class);
            when(user.getEmail()).thenReturn("member@example.invalid");
            SocialEmail.verify(user, context);
            verify(user, never()).setEmailVerified(anyBoolean());
            verify(user, never()).removeRequiredAction(any(UserModel.RequiredAction.class));
        }
        var config = new OAuth2IdentityProviderConfig(); config.setEnabled(true);
        var context = new BrokeredIdentityContext("stable-id", config);
        context.setAuthenticationSession(mock(AuthenticationSessionModel.class));
        var user = mock(UserModel.class);
        when(user.getEmail()).thenReturn("member@example.invalid");
        SocialEmail.verify(user, context);
        context.setEmail("member@example.invalid");
        when(context.getAuthenticationSession().getAuthNote(AbstractIdentityProvider.UPDATE_PROFILE_EMAIL_CHANGED)).thenReturn("true");
        SocialEmail.verify(user, context);
        context.setAuthenticationSession(null);
        SocialEmail.verify(user, context);
        verify(user, never()).setEmailVerified(anyBoolean());
        verify(user, never()).removeRequiredAction(any(UserModel.RequiredAction.class));
    }

    @Test void ordinaryLoginStillRequiresEmailVerification() {
        var required = mock(RequiredActionContext.class);
        var realm = mock(RealmModel.class);
        var user = mock(UserModel.class);
        when(required.getRealm()).thenReturn(realm);
        when(required.getUser()).thenReturn(user);
        when(realm.isVerifyEmail()).thenReturn(true);
        when(user.getEmail()).thenReturn("member@example.invalid");
        when(user.getRequiredActionsStream()).thenAnswer(invocation -> Stream.empty());
        new VerifyEmail().evaluateTriggers(required);
        verify(user).addRequiredAction(UserModel.RequiredAction.VERIFY_EMAIL);
        verify(user, never()).setEmailVerified(anyBoolean());
    }
}
