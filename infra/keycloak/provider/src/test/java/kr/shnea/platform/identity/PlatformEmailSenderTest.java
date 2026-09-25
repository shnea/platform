package kr.shnea.platform.identity;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.keycloak.email.EmailException;
import org.keycloak.models.KeycloakContext;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;

class PlatformEmailSenderTest {
    @Test void onlyEnabledOwnedProjectRealmsCanReachDelivery() throws Exception {
        var session = mock(KeycloakSession.class);
        var context = mock(KeycloakContext.class);
        var realm = mock(RealmModel.class);
        when(session.getContext()).thenReturn(context);
        when(context.getRealm()).thenReturn(realm);
        var sender = new PlatformEmailSender(session);
        assertThrows(EmailException.class, () -> sender.validate(Map.of()));
        String id = UUID.randomUUID().toString();
        when(realm.isEnabled()).thenReturn(true);
        when(realm.getAttribute("platform.environmentId")).thenReturn(id);
        when(realm.getName()).thenReturn("p-" + id.replace("-", ""));
        sender.validate(Map.of());
        assertThrows(EmailException.class, () -> sender.send(Map.of(), (String) null, "test", "", ""));
        when(realm.getName()).thenReturn("foreign-realm");
        assertThrows(EmailException.class, () -> sender.validate(Map.of()));
        when(realm.getName()).thenReturn("p-" + id.replace("-", ""));
        when(realm.isEnabled()).thenReturn(false);
        assertThrows(EmailException.class, () -> sender.validate(Map.of()));
    }
}
