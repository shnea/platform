package kr.shnea.platform.project;

import jakarta.servlet.Filter;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.*;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import tools.jackson.databind.json.JsonMapper;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AiSecurityTest {
    @Configuration @EnableWebSecurity @EnableWebMvc
    @Import({SecurityConfig.class, ApiProblems.class, ApiErrors.class, AiController.class, AiJobController.class, AiIndexingController.class, AiCompletion.class})
    static class Config {
        @Bean ProjectService projects() { return mock(ProjectService.class); }
        @Bean AiGateway ai() { return mock(AiGateway.class); }
        @Bean AiJobs jobs() { return mock(AiJobs.class); }
        @Bean AiIndexing indexing() { return mock(AiIndexing.class); }
        @Bean org.springframework.jdbc.core.JdbcTemplate db() { return mock(org.springframework.jdbc.core.JdbcTemplate.class); }
        @Bean org.springframework.transaction.support.TransactionTemplate tx() { return mock(org.springframework.transaction.support.TransactionTemplate.class); }
        @Bean JsonMapper json() { return new JsonMapper(); }
        @Bean JwtDecoder decoder() {
            return token -> Jwt.withTokenValue(token).header("alg", "RS256").subject("user")
                .claim("realm_access", Map.of("roles", List.of(token.equals("admin") ? "platform-admin" : "reader"))).build();
        }
    }
    @Test void keysWorkWithoutUserLoginAndBearerCannotReplaceFeatureScopes() throws Exception {
        try (var context = new AnnotationConfigWebApplicationContext()) {
            context.setServletContext(new MockServletContext()); context.register(Config.class); context.refresh();
            var mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(context.getBean(kr.shnea.platform.http.RequestTrace.class), context.getBean("springSecurityFilterChain", Filter.class)).build();
            var projects = context.getBean(ProjectService.class); var ai = context.getBean(AiGateway.class);
            for (String scope : List.of("ai:read", "ai:route", "ai:embed", "ai:execute", "ai:jobs:read", "ai:cancel", "ai:usage", "ai:index:write", "ai:index:read", "ai:index:search")) {
                when(projects.context(null, scope)).thenThrow(ApiCode.INVALID_API_KEY.failure());
                when(projects.context("old-key", scope)).thenThrow(ApiCode.INSUFFICIENT_SCOPE.failure());
                when(projects.context("scoped-key", scope)).thenReturn(new ProjectService.Context(UUID.randomUUID(), UUID.randomUUID(), "DEV", "https://identity.example", List.of(scope)));
            }
            for (String path : List.of("/api/v1/ai/raya/route", "/api/v1/ai/embeddings", "/api/v1/ai/jobs", "/api/v1/translations", "/api/v1/ai/jobs/" + UUID.randomUUID() + "/receipt", "/api/v1/ai/indexing", "/api/v1/ai/indexing/search")) {
                mvc.perform(post(path).contentType("application/json").content("{}")).andExpect(status().isUnauthorized());
                mvc.perform(post(path).header("Authorization", "Bearer admin").contentType("application/json").content("{}"))
                    .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("INVALID_API_KEY"));
                mvc.perform(post(path).header("X-Platform-Key", "old-key").contentType("application/json").content("{}"))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("INSUFFICIENT_SCOPE"));
            }
            var jobs = context.getBean(AiJobs.class);
            for (String path : List.of("/api/v1/ai/jobs", "/api/v1/ai/jobs/" + UUID.randomUUID(), "/api/v1/ai/events", "/api/v1/ai/jobs/" + UUID.randomUUID() + "/translation.txt", "/api/v1/ai/usage", "/api/v1/ai/indexing", "/api/v1/ai/indexing/collections", "/api/v1/ai/indexing/" + UUID.randomUUID())) {
                mvc.perform(get(path)).andExpect(status().isUnauthorized());
                mvc.perform(get(path).header("X-Platform-Key", "old-key")).andExpect(status().isForbidden());
                mvc.perform(get(path).header("Authorization", "Bearer admin")).andExpect(status().isUnauthorized());
            }
            mvc.perform(post("/api/v1/ai/jobs/" + UUID.randomUUID() + "/cancel")).andExpect(status().isUnauthorized());
            mvc.perform(post("/api/v1/ai/indexing/" + UUID.randomUUID() + "/cancel")).andExpect(status().isUnauthorized());
            verifyNoInteractions(jobs, context.getBean(AiIndexing.class));
            mvc.perform(get("/api/v1/ai/jobs").header("X-Platform-Key", "scoped-key").param("project", "other"))
                .andExpect(status().isUnprocessableEntity());
            mvc.perform(get("/api/v1/ai/usage").header("X-Platform-Key", "scoped-key").param("environment", "other"))
                .andExpect(status().isUnprocessableEntity());
            verifyNoInteractions(jobs);
            mvc.perform(get("/api/v1/ai/jobs").header("X-Platform-Key", "scoped-key")).andExpect(status().isOk());
            verify(jobs).list(any(), isNull(), eq(50));
            mvc.perform(post("/api/v1/ai/jobs").header("X-Platform-Key", "scoped-key").contentType("application/json").content("{}"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"));
            verify(projects).context("scoped-key", "ai:execute");
            mvc.perform(post("/api/v1/translations").header("X-Platform-Key", "scoped-key").contentType("application/json").content("{}"))
                .andExpect(status().isAccepted()).andExpect(header().string("Cache-Control", "no-store"));
            verify(jobs).translate(any(), any());
            mvc.perform(post("/api/webhooks/noedaeri/ai").contentType("application/json").content("{}"))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("AI_DELIVERY_NOT_CONFIGURED"));
            mvc.perform(get("/api/v1/ai/services")).andExpect(status().isUnauthorized());
            mvc.perform(get("/api/v1/admin/ai/services")).andExpect(status().isUnauthorized());
            mvc.perform(get("/api/v1/admin/ai/services").header("Authorization", "Bearer reader")).andExpect(status().isForbidden());
            verifyNoInteractions(ai);
            when(ai.embeddings(any())).thenReturn(new JsonMapper().readTree("{\"dimensions\":768}"));
            mvc.perform(post("/api/v1/ai/embeddings").header("X-Platform-Key", "scoped-key").contentType("application/json").content("{\"input\":\"x\"}"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store")).andExpect(jsonPath("$.dimensions").value(768));
            verify(projects).context("scoped-key", "ai:embed");
            mvc.perform(post("/api/v1/ai/raya/route").header("X-Platform-Key", "scoped-key").contentType("application/json").content("x".repeat(AiGateway.ROUTE_LIMIT + 1)))
                .andExpect(status().isPayloadTooLarge());
            verify(ai, never()).route(any());
            when(ai.services()).thenReturn(Map.of("configured", false));
            mvc.perform(get("/api/v1/admin/ai/services").header("Authorization", "Bearer admin")).andExpect(status().isOk());
            mvc.perform(post("/api/v1/ai/not-implemented").header("X-Platform-Key", "scoped-key")).andExpect(status().isUnauthorized());
        }
    }
}
