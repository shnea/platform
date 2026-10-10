package kr.shnea.platform.identity;

import org.keycloak.broker.provider.AbstractIdentityProvider;
import org.keycloak.broker.provider.BrokeredIdentityContext;
import org.keycloak.models.UserModel;

final class SocialEmail {
    private SocialEmail() {}

    static void verify(UserModel user, BrokeredIdentityContext context) {
        var authSession = context.getAuthenticationSession();
        String email = context.getEmail();
        if (authSession == null || email == null || email.isBlank() || !email.equalsIgnoreCase(user.getEmail())
                || Boolean.parseBoolean(authSession.getAuthNote(AbstractIdentityProvider.UPDATE_PROFILE_EMAIL_CHANGED))) return;
        user.setEmailVerified(true);
        user.removeRequiredAction(UserModel.RequiredAction.VERIFY_EMAIL);
        authSession.removeRequiredAction(UserModel.RequiredAction.VERIFY_EMAIL.name());
    }
}
