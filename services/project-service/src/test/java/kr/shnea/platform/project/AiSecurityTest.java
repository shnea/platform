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
    @Import({SecurityConfig.class, ApiProblems.class, ApiErrors.class, AiController.class})
    static class Config {
        @Bean ProjectService projects() { return mock(ProjectService.class); }
        @Bean AiGateway ai() { return mock(AiGateway.class); }
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
            for (String scope : List.of("ai:read", "ai:route", "ai:embed")) {
                when(projects.context(null, scope)).thenThrow(ApiCode.INVALID_API_KEY.failure());
                when(projects.context("old-key", scope)).thenThrow(ApiCode.INSUFFICIENT_SCOPE.failure());
            }
            for (String path : List.of("/api/v1/ai/raya/route", "/api/v1/ai/embeddings")) {
                mvc.perform(post(path).contentType("application/json").content("{}")).andExpect(status().isUnauthorized());
                mvc.perform(post(path).header("Authorization", "Bearer admin").contentType("application/json").content("{}"))
                    .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("INVALID_API_KEY"));
                mvc.perform(post(path).header("X-Platform-Key", "old-key").contentType("application/json").content("{}"))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("INSUFFICIENT_SCOPE"));
            }
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
