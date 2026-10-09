package kr.shnea.platform.project;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

class AiConfigurationTest {
    @Test void springCreatesRuntimeAdapterWithEncryptedEnvironmentBindings() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("ai-settings", Map.of(
                "platform.noedaeri.url", "https://example.invalid", "platform.noedaeri.key", "test-key", "platform.noedaeri.webhook-secret", "test-secret")));
            registerComponents(context); context.refresh();
            assertThat(context.getBean(AiGateway.class).configured()).isTrue();
            assertThat(context.getBean(AiGateway.class).completionReceiverConfigured()).isTrue();
            assertThat(context.getBean(AiCompletion.class)).isNotNull();
            assertThat(context.getBean(AiJobs.class)).isNotNull();
            assertThat(context.getBean(AiJobController.class)).isNotNull();
            assertThat(context.getBean(AiIndexingController.class)).isNotNull();
        }
    }
    @Test void missingAiSettingsDoNotPreventOtherPlatformFeaturesFromStarting() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("ai-settings", Map.of(
                "platform.noedaeri.url", "", "platform.noedaeri.key", "")));
            registerComponents(context); context.refresh();
            assertThat(context.getBean(AiGateway.class).configured()).isFalse();
            assertThat(context.getBean(AiGateway.class).completionReceiverConfigured()).isFalse();
            assertThat(context.getBean(AiJobs.class)).isNotNull();
            assertThat(context.getBean(AiController.class)).isNotNull();
        }
    }
    private static void registerComponents(AnnotationConfigApplicationContext context) {
        context.registerBean(org.springframework.jdbc.core.JdbcTemplate.class, () -> mock(org.springframework.jdbc.core.JdbcTemplate.class));
        context.registerBean(org.springframework.transaction.support.TransactionTemplate.class, () -> mock(org.springframework.transaction.support.TransactionTemplate.class));
        context.registerBean(ProjectService.class, () -> mock(ProjectService.class));
        context.register(AiGateway.class, AiJobs.class, AiJobController.class, AiController.class, AiIndexing.class, AiIndexingController.class, AiCompletion.class);
    }
}
