package kr.shnea.platform.identity;

import org.keycloak.broker.provider.BrokeredIdentityContext;

final class SocialNickname {
    private SocialNickname() {}

    static void requireUserChoice(BrokeredIdentityContext context) {
        // Do not use a provider's real name as the service nickname. IMPORT mode
        // preserves existing members; new members complete the required firstName.
        context.setFirstName(null);
        context.setLastName(null);
    }
}
