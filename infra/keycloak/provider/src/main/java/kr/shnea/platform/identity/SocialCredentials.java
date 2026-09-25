package kr.shnea.platform.identity;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import org.keycloak.models.IdentityProviderModel;

/** Secrets live in the Keycloak container environment, never in project settings/API responses. */
final class SocialCredentials {
    static Map<String, Boolean> readiness() {
        return Map.of("naver", ready("naver", System.getenv()), "google", ready("google", System.getenv()),
            "kakao", ready("kakao", System.getenv()));
    }
    static boolean ready(String provider, Map<String, String> env) {
        String prefix = "SOCIAL_" + provider.toUpperCase(Locale.ROOT);
        return !env.getOrDefault(prefix + "_CLIENT_ID", "").isBlank()
            && !env.getOrDefault(prefix + "_CLIENT_SECRET", "").isBlank();
    }
    static void apply(IdentityProviderModel config, String provider) { apply(config, provider, System.getenv()); }
    static void apply(IdentityProviderModel config, String provider, Map<String, String> env) {
        if (!SharedSocialCallback.enabled(config)) return;
        String prefix = "SOCIAL_" + provider.toUpperCase(Locale.ROOT);
        var values = new HashMap<>(config.getConfig());
        values.put("clientId", env.getOrDefault(prefix + "_CLIENT_ID", "").strip());
        values.put("clientSecret", env.getOrDefault(prefix + "_CLIENT_SECRET", ""));
        config.setConfig(values);
    }
}
