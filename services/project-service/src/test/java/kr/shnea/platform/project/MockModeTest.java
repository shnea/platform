package kr.shnea.platform.project;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class MockModeTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withBean(ProjectService.class, () -> mock(ProjectService.class))
        .withUserConfiguration(MockController.class);

    @Test void productionDoesNotRegisterMockController() {
        runner.withPropertyValues("platform.mode=prod").run(context ->
            assertThat(context).doesNotHaveBean(MockController.class));
    }
    @Test void developmentRegistersMockController() {
        runner.withPropertyValues("platform.mode=dev").run(context ->
            assertThat(context).hasSingleBean(MockController.class));
    }
}
