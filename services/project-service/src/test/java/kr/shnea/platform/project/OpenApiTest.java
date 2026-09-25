package kr.shnea.platform.project;

import java.util.HashSet;
import java.util.Set;
import java.util.Map;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.assertThat;

class OpenApiTest {
    private final JsonMapper json = new JsonMapper();

    @Test void documentsEveryExternalOperationAndSeparatesProductionFromDevelopment() throws Exception {
        var dev = new OpenApiController(json, "dev").specification().getBody();
        var prod = new OpenApiController(json, "prod").specification().getBody();
        Set<String> actual = new HashSet<>(), documented = new HashSet<>();
        for (Class<?> controller : new Class<?>[]{ProjectController.class, PublicConfigController.class,
                EmailController.class, MockController.class, OpenApiController.class}) {
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
        assertThat(dev.path("paths").size()).isEqualTo(25);
        assertThat(prod.path("paths").size()).isEqualTo(20);
        assertThat(prod.path("paths").has("/internal/v1/email/environments/{id}")).isFalse();
    }

    @Test void recordFieldsAndSchemaPropertiesStayInSync() throws Exception {
        var schemas = new OpenApiController(json, "dev").specification().getBody().path("components").path("schemas");
        var models = Map.ofEntries(
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
