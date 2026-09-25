package kr.shnea.platform.project;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

class ProjectServiceTest {
    @Test void callbacksRejectWildcardsCredentialsAndInsecureProduction() {
        for (String uri : List.of("http://example.com/cb", "https://example.com/*", "javascript:alert(1)",
                "https://user:pass@example.com/cb", "https://example.com/cb#fragment", "//example.com/cb")) {
            assertThrows(ResponseStatusException.class, () -> ProjectService.validateRedirects("PROD", List.of(uri)));
        }
        assertThrows(ResponseStatusException.class, () -> ProjectService.validateRedirects("PROD", List.of("http://localhost/cb")));
        assertThrows(ResponseStatusException.class, () -> ProjectService.validateRedirects("DEV", List.of("http://127.0.0.1.evil.test/cb")));
        assertDoesNotThrow(() -> ProjectService.validateRedirects("DEV", List.of("http://localhost:3000/cb")));
        assertDoesNotThrow(() -> ProjectService.validateRedirects("PROD", List.of("https://example.com/cb")));
    }
}
