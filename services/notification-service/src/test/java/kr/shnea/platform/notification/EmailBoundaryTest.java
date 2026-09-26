package kr.shnea.platform.notification;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;

class EmailBoundaryTest {
    @Test void rejectsUnauthenticatedAndInvalidRequestsWithoutEchoingPayloads() throws Exception {
        String secret = "test-mail-secret-" + "x".repeat(32);
        var db = mock(JdbcTemplate.class);
        var ncp = mock(NcpMail.class);
        var mvc = MockMvcBuilders.standaloneSetup(new EmailController(db, mock(TransactionTemplate.class), ncp,
            "prod", secret, "http://127.0.0.1:1"))
            .setControllerAdvice(new EmailErrors()).addFilters(new kr.shnea.platform.http.RequestTrace(),
                new InternalSecurity(secret, "", new tools.jackson.databind.json.JsonMapper())).build();
        assertEquals(403, mvc.perform(get("/internal/v1/email/readiness")).andReturn().getResponse().getStatus());
        assertEquals(404, mvc.perform(get("/internal/v1/email/inbox/" + UUID.randomUUID()).header("X-Platform-Mail-Key", secret)).andReturn().getResponse().getStatus());
        var invalid = mvc.perform(post("/internal/v1/email").header("X-Platform-Mail-Key", secret)
            .contentType("application/json").content("{\"recipient\":\"private-action-token\"}"))
            .andReturn().getResponse();
        assertEquals(400, invalid.getStatus());
        assertFalse(invalid.getContentAsString().contains("private-action-token"));
        assertEquals("no-store", invalid.getHeader("Cache-Control"));
        verifyNoInteractions(db, ncp);
    }
}
