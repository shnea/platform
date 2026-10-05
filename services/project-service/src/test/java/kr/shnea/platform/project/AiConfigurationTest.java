package kr.shnea.platform.project;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import static org.assertj.core.api.Assertions.*;

class AiConfigurationTest {
    @Test void springCreatesRuntimeAdapterWithEncryptedEnvironmentBindings() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("ai-settings", Map.of(
                "platform.noedaeri.url", "https://example.invalid", "platform.noedaeri.key", "test-key")));
            context.register(AiGateway.class); context.refresh();
            assertThat(context.getBean(AiGateway.class).configured()).isTrue();
        }
    }
    @Test void missingAiSettingsDoNotPreventOtherPlatformFeaturesFromStarting() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("ai-settings", Map.of(
                "platform.noedaeri.url", "", "platform.noedaeri.key", "")));
            context.register(AiGateway.class); context.refresh();
            assertThat(context.getBean(AiGateway.class).configured()).isFalse();
        }
    }
}
