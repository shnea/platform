package kr.shnea.platform.project;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

record SocialProvider(String code, String label, String providerId) {
    static final List<SocialProvider> ALL = List.of(
        new SocialProvider("kakao", "카카오", "platform-kakao"),
        new SocialProvider("naver", "네이버", "platform-naver"),
        new SocialProvider("google", "구글", "platform-google"));

    static SocialProvider find(String code) {
        return ALL.stream().filter(p -> p.code.equals(code)).findFirst()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown social provider"));
    }

    String alias() { return "platform-" + code; }

    Map<String, String> config() {
        var config = new HashMap<String, String>();
        config.put("syncMode", "IMPORT");
        config.put("clientAuthMethod", "client_secret_post");
        config.put("platform.callbackMode", "shared-v1");
        if (code.equals("kakao")) {
            config.put("authorizationUrl", "https://kauth.kakao.com/oauth/authorize");
            config.put("tokenUrl", "https://kauth.kakao.com/oauth/token");
            config.put("userInfoUrl", "https://kapi.kakao.com/v1/oidc/userinfo");
            config.put("issuer", "https://kauth.kakao.com");
            config.put("jwksUrl", "https://kauth.kakao.com/.well-known/jwks.json");
            config.put("useJwksUrl", "true");
            config.put("validateSignature", "true");
            config.put("defaultScope", "openid");
            config.put("pkceEnabled", "true");
            config.put("pkceMethod", "S256");
        }
        return config;
    }

    boolean acceptsProviderId(Object id) {
        return providerId.equals(id) || (code.equals("kakao") && "oidc".equals(id))
            || (code.equals("google") && "google".equals(id));
    }

    record Metadata(String code, String label, String alias, boolean configured, boolean enabled,
                    boolean credentialsConfigured, String revision, String callbackUrl,
                    boolean activationAllowed, boolean sharedCallback, String sharedCallbackUrl) {}
}
