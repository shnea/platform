package kr.shnea.platform.identity;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.keycloak.models.IdentityProviderModel;
import static org.junit.jupiter.api.Assertions.*;

class SocialCredentialsTest {
    @Test void commonCredentialsOverrideLegacyValuesWithoutMutatingStoredConfiguration() {
        Map<String, String> stored = Map.of("platform.callbackMode", "shared-v1", "clientId", "old", "clientSecret", "old-secret");
        var config = new IdentityProviderModel();
        config.setConfig(stored);
        Map<String, String> env = Map.of("SOCIAL_NAVER_CLIENT_ID", "common", "SOCIAL_NAVER_CLIENT_SECRET", "common-secret");
        assertTrue(SocialCredentials.ready("naver", env));
        assertFalse(SocialCredentials.ready("google", env));
        SocialCredentials.apply(config, "naver", env);
        assertEquals("common", config.getConfig().get("clientId"));
        assertEquals("common-secret", config.getConfig().get("clientSecret"));
        assertEquals("old-secret", stored.get("clientSecret"));
        SocialCredentials.apply(config, "naver", Map.of());
        assertTrue(config.getConfig().get("clientId").isEmpty());
        assertTrue(config.getConfig().get("clientSecret").isEmpty());
    }
    @Test void googleConfigurationRetainsNativeIssuerValidationDefaults() {
        var config = new GoogleIdentityProviderFactory().createConfig();
        assertDoesNotThrow(() -> config.validate(null));
        assertEquals("https://accounts.google.com", config.getIssuer());
    }
}
