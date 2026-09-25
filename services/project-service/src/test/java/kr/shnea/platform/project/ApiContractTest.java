package kr.shnea.platform.project;

import kr.shnea.platform.http.RequestTrace;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClientException;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ApiContractTest {
    private MockMvc mvc;
    @BeforeEach void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new Probe()).setControllerAdvice(new ApiErrors())
            .addFilters(new RequestTrace()).build();
    }

    @RestController static class Probe {
        record Input(@NotBlank String name) {}
        @PostMapping("/api/v1/probe") Map<String, String> validate(@Valid @RequestBody Input input) {
            return Map.of("result", "ok");
        }
        @GetMapping("/api/v1/probe/{kind}") void fail(@PathVariable String kind) {
            switch (kind) {
                case "conflict" -> throw ApiCode.MOCK_RESET_CHANGED.failure();
                case "database" -> throw new DataIntegrityViolationException("sql-password-secret");
                case "upstream" -> throw new RestClientException("provider-token-secret");
                default -> throw new IllegalStateException("private-implementation-secret");
            }
        }
    }

    @Test void businessConflictHasStableCodeKoreanDetailAndMatchingTrace() throws Exception {
        String id = "1234567890abcdef1234567890abcdef";
        mvc.perform(get("/api/v1/probe/conflict?token=query-secret").header(RequestTrace.HEADER, id))
            .andExpect(status().isConflict()).andExpect(content().contentTypeCompatibleWith("application/problem+json"))
            .andExpect(header().string(RequestTrace.HEADER, id))
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.type").value("urn:shnea:platform:error:MOCK_RESET_CHANGED"))
            .andExpect(jsonPath("$.instance").value("urn:shnea:platform:request:" + id))
            .andExpect(jsonPath("$.code").value("MOCK_RESET_CHANGED"))
            .andExpect(jsonPath("$.requestId").value(id))
            .andExpect(jsonPath("$.status").value(409))
            .andExpect(jsonPath("$.detail").value(ApiCode.MOCK_RESET_CHANGED.detail));
    }

    @Test void invalidPayloadDoesNotEchoRejectedValuesAndHasKoreanFieldErrors() throws Exception {
        mvc.perform(post("/api/v1/probe").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"\"}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
            .andExpect(jsonPath("$.errors[0].field").value("name"))
            .andExpect(jsonPath("$.errors[0].message").value("필수 항목을 입력해 주세요."))
            .andExpect(jsonPath("$.errors[0].rejectedValue").doesNotExist());
        var response = mvc.perform(post("/api/v1/probe").contentType(MediaType.APPLICATION_JSON)
                .content("{\"secret\":\"raw-input-secret\","))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
            .andReturn().getResponse();
        assertThat(response.getContentAsString()).doesNotContain("raw-input-secret", "JsonParse", "stackTrace");
    }

    @Test void internalFailuresAreSanitized() throws Exception {
        for (var item : Map.of("database", 409, "upstream", 502, "unknown", 500).entrySet()) {
            var response = mvc.perform(get("/api/v1/probe/" + item.getKey()))
                .andExpect(status().is(item.getValue())).andExpect(jsonPath("$.requestId").isString())
                .andReturn().getResponse();
            assertThat(response.getContentAsString()).doesNotContain("secret", "Exception", "stackTrace");
        }
    }

    @Test void methodAndMediaErrorsKeepHttpSemantics() throws Exception {
        mvc.perform(delete("/api/v1/probe")).andExpect(status().isMethodNotAllowed())
            .andExpect(header().string("Allow", "POST")).andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
        mvc.perform(post("/api/v1/probe").contentType(MediaType.TEXT_PLAIN).content("input-secret"))
            .andExpect(status().isUnsupportedMediaType()).andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
    }

    @Test void malformedDuplicateAndMissingIdsAreReplacedAndMdcIsRestored() throws Exception {
        MDC.put("unrelated", "preserved");
        try {
            for (String input : new String[]{"", "unsafe-request-id", "a".repeat(200)}) {
                var response = mvc.perform(get("/api/v1/probe/conflict").header(RequestTrace.HEADER, input))
                    .andReturn().getResponse();
                assertThat(response.getHeader(RequestTrace.HEADER)).matches("[a-f0-9]{32}").isNotEqualTo(input);
            }
            String duplicate = "1".repeat(32);
            var response = mvc.perform(get("/api/v1/probe/conflict").header(RequestTrace.HEADER, duplicate, duplicate))
                .andReturn().getResponse();
            assertThat(response.getHeader(RequestTrace.HEADER)).isNotEqualTo(duplicate);
            assertThat(MDC.get("requestId")).isNull();
            assertThat(MDC.get("errorCode")).isNull();
            assertThat(MDC.get("unrelated")).isEqualTo("preserved");
        } finally { MDC.clear(); }
    }
}
