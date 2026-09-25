package kr.shnea.platform.project;

import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class PublicConfigController {
    private final Map<String, String> config;
    PublicConfigController(@Value("${platform.identity.public-url}") String url, @Value("${platform.mode}") String mode) {
        config = Map.of("url", url, "realm", "platform-admin-" + mode, "clientId", "platform-admin-web", "mode", mode);
    }
    @GetMapping("/api/v1/config")
    Map<String, String> config() { return config; }
}
