package kr.shnea.platform.identity;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.keycloak.broker.oidc.OAuth2IdentityProviderConfig;
import org.keycloak.broker.provider.IdentityBrokerException;
import static org.junit.jupiter.api.Assertions.*;

class NaverIdentityProviderTest {
    private final ObjectMapper json = new ObjectMapper();
    private final OAuth2IdentityProviderConfig config = new OAuth2IdentityProviderConfig();
    NaverIdentityProviderTest() { config.setEnabled(true); }

    @Test void identityUsesProviderIdNotMutableEmail() throws Exception {
        var first = NaverIdentityProvider.parseProfile(json.readTree("""
            {"resultcode":"00","response":{"id":"stable-id","email":"first@example.invalid","name":"Test"}}
            """), config);
        var changed = NaverIdentityProvider.parseProfile(json.readTree("""
            {"resultcode":"00","response":{"id":"stable-id","email":"changed@example.invalid"}}
            """), config);
        assertEquals("stable-id", first.getId());
        assertEquals(first.getUsername(), changed.getUsername());
        assertEquals("first@example.invalid", first.getEmail());
        var withoutEmail = NaverIdentityProvider.parseProfile(json.readTree("""
            {"resultcode":"00","response":{"id":"another-id"}}
            """), config);
        assertNull(withoutEmail.getEmail());
        assertNotEquals(first.getUsername(), withoutEmail.getUsername());
    }

    @Test void rejectsProviderErrorsAndInvalidIdentities() {
        for (String value : new String[]{"null", "{}", "{\"resultcode\":\"024\"}",
                "{\"resultcode\":\"00\",\"response\":{\"id\":\" \"}}",
                "{\"resultcode\":\"00\",\"response\":{\"id\":42}}"}) {
            assertThrows(IdentityBrokerException.class, () -> NaverIdentityProvider.parseProfile(json.readTree(value), config));
        }
    }
}
