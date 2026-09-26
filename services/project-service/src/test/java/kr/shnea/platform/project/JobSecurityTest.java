package kr.shnea.platform.project;

import jakarta.servlet.Filter;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import tools.jackson.databind.json.JsonMapper;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class JobSecurityTest {
    @Configuration @EnableWebSecurity @EnableWebMvc
    @Import({SecurityConfig.class, ApiProblems.class, ApiErrors.class, JobController.class, OutboxController.class, OperationalAlertController.class})
    static class Config {
        @Bean ProvisionJobs jobs() { return mock(ProvisionJobs.class); }
        @Bean OutboxDelivery delivery() { return mock(OutboxDelivery.class); }
        @Bean ProjectService projects() { return mock(ProjectService.class); }
        @Bean JsonMapper json() { return new JsonMapper(); }
        // Verify the real authorization filter with a decoded JWT lacking the administrator role.
        @Bean JwtDecoder decoder() {
            return token -> Jwt.withTokenValue(token).header("alg", "RS256").subject("reader")
                .claim("realm_access", Map.of("roles", List.of("reader"))).build();
        }
    }

    @Test void everyJobOperationRejectsAnonymousAndNonAdministratorBeforeBusinessCode() throws Exception {
        try (var context = new AnnotationConfigWebApplicationContext()) {
            context.setServletContext(new MockServletContext());
            context.register(Config.class);
            context.refresh();
            var mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(context.getBean("springSecurityFilterChain", Filter.class)).build();
            String id = UUID.randomUUID().toString();
            for (String suffix : List.of("/environments/"+id+"/provision-jobs", "/jobs/"+id+"/cancel", "/jobs/"+id+"/retry", "/events/"+id+"/retry", "/environments/"+id+"/operational-alerts/"+id+"/acknowledge")) {
                mvc.perform(post("/api/v1/admin"+suffix)).andExpect(status().isUnauthorized());
                mvc.perform(post("/api/v1/admin"+suffix).header("Authorization", "Bearer reader"))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
            }
            for (String suffix : List.of("/jobs", "/jobs/"+id, "/jobs/"+id+"/events", "/environments/"+id+"/operational-alerts")) {
                mvc.perform(get("/api/v1/admin"+suffix)).andExpect(status().isUnauthorized());
                mvc.perform(get("/api/v1/admin"+suffix).header("Authorization", "Bearer reader"))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
            }
            verifyNoInteractions(context.getBean(ProvisionJobs.class));
            verifyNoInteractions(context.getBean(OutboxDelivery.class));
            verifyNoInteractions(context.getBean(ProjectService.class));
        }
    }
}
