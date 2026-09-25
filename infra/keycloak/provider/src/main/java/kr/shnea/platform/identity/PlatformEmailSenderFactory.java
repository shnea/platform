package kr.shnea.platform.identity;

import org.keycloak.Config;
import org.keycloak.email.EmailSenderProvider;
import org.keycloak.email.EmailSenderProviderFactory;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;

public class PlatformEmailSenderFactory implements EmailSenderProviderFactory {
    @Override public EmailSenderProvider create(KeycloakSession session) { return new PlatformEmailSender(session); }
    @Override public String getId() { return "platform-notification"; }
    @Override public int order() { return 100; }
    @Override public void init(Config.Scope config) {}
    @Override public void postInit(KeycloakSessionFactory factory) {}
    @Override public void close() {}
}
