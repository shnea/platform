package kr.shnea.platform.project;

import java.util.HashSet;
import java.util.Set;
import java.util.Map;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.assertThat;

class OpenApiTest {
    private final JsonMapper json = new JsonMapper();

    @Test void documentsEveryExternalOperationAndSeparatesProductionFromDevelopment() throws Exception {
        var dev = new OpenApiController(json, "dev").specification().getBody();
        var prod = new OpenApiController(json, "prod").specification().getBody();
        Set<String> actual = new HashSet<>(), documented = new HashSet<>();
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        var environment = new org.springframework.core.env.StandardEnvironment();
        environment.getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource(
            "contract-mode", Map.of("platform.mode", "dev")));
        scanner.setEnvironment(environment);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        for (var bean : scanner.findCandidateComponents("kr.shnea.platform.project")) {
            Class<?> controller = Class.forName(bean.getBeanClassName());
            // Test fixture controllers share this package but are not shipped in the service.
            if (!controller.getProtectionDomain().getCodeSource().getLocation()
                    .equals(ProjectController.class.getProtectionDomain().getCodeSource().getLocation())) continue;
            for (var method : controller.getDeclaredMethods()) {
                var mapping = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
                if (mapping == null) continue;
                for (String path : mapping.path()) if (path.startsWith("/api/v1/"))
                    for (var verb : mapping.method()) actual.add(verb.name().toLowerCase() + " " + path);
            }
        }
        for (String path : dev.path("paths").propertyNames()) {
            var item = dev.path("paths").path(path);
            for (String verb : item.propertyNames()) {
                documented.add(verb + " " + path);
                var operation = item.path(verb);
                assertThat(operation.path("summary").asText()).isNotBlank();
                assertThat(operation.path("responses").isEmpty()).isFalse();
                assertThat(operation.has("security")).isTrue();
                if (operation.path("x-development-only").asBoolean(false))
                    assertThat(prod.path("paths").path(path).has(verb)).isFalse();
                else assertThat(prod.path("paths").path(path).has(verb)).isTrue();
            }
        }
        assertThat(documented).isEqualTo(actual);
        assertThat(dev.path("paths").size()).isEqualTo(36);
        assertThat(prod.path("paths").size()).isEqualTo(31);
        assertThat(prod.path("paths").has("/internal/v1/email/environments/{id}")).isFalse();
    }

    @Test void recordFieldsAndSchemaPropertiesStayInSync() throws Exception {
        var schemas = new OpenApiController(json, "dev").specification().getBody().path("components").path("schemas");
        var models = Map.ofEntries(
            Map.entry("OperationalAlert",OperationalAlertController.Alert.class),
            Map.entry("AlertEmailSettings",OperationalAlertController.EmailSettings.class),
            Map.entry("AlertEmailSettingsUpdate",OperationalAlertController.EmailSettingsUpdate.class),
            Map.entry("AlertEmailDelivery",OperationalAlertController.EmailDelivery.class),
            Map.entry("EventDelivery",OutboxDelivery.Delivery.class), Map.entry("EventAttempt",OutboxDelivery.Attempt.class),
            Map.entry("EventDeliveryDetail",OutboxDelivery.Detail.class),
            Map.entry("Job", ProvisionJobs.Job.class), Map.entry("JobAttempt", ProvisionJobs.Attempt.class),
            Map.entry("JobDetail", ProvisionJobs.Detail.class),
            Map.entry("Project", ProjectService.Project.class), Map.entry("Environment", ProjectService.Environment.class),
            Map.entry("Credential", ProjectService.Credential.class), Map.entry("Context", ProjectService.Context.class),
            Map.entry("Member", Member.class), Map.entry("MemberPage", Member.Page.class),
            Map.entry("MemberDetail", Member.Detail.class), Map.entry("Session", Member.Session.class),
            Map.entry("AuthenticationPolicy", AuthenticationPolicy.class), Map.entry("SocialProvider", SocialProvider.Metadata.class),
            Map.entry("ResetRequest", MockController.Reset.class), Map.entry("ResetPreview", MockReset.Preview.class),
            Map.entry("ResetResult", MockReset.Result.class), Map.entry("NewProject", ProjectController.NewProject.class),
            Map.entry("NewEnvironment", ProjectController.NewEnvironment.class), Map.entry("NewCredential", ProjectController.NewCredential.class),
            Map.entry("ProjectSettings", ProjectController.ProjectSettings.class), Map.entry("EnvironmentSettings", ProjectController.EnvironmentSettings.class),
            Map.entry("MemberState", ProjectController.MemberState.class), Map.entry("SocialSettings", ProjectController.SocialSettings.class),
            Map.entry("AuthenticationSettings", ProjectController.AuthenticationSettings.class));
        models.forEach((name, type) -> assertThat(schemas.path(name).path("properties").propertyNames())
            .as(name).containsExactlyInAnyOrderElementsOf(Arrays.stream(type.getRecordComponents()).map(c -> c.getName()).toList()));
    }
}
