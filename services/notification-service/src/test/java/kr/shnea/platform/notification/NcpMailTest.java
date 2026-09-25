package kr.shnea.platform.notification;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.env.MockEnvironment;

class NcpMailTest {
    @Test @SuppressWarnings("unchecked") void acceptsOnlyConfirmedSingleRequestAndNeverRetriesAmbiguousFailures() throws Exception {
        var env = new MockEnvironment().withProperty("NCP_ACCESS_KEY", "access").withProperty("NCP_SECRET_KEY", "secret")
            .withProperty("NCP_MAIL_SENDER_ADDRESS", "sender@example.invalid");
        var http = mock(HttpClient.class);
        HttpResponse<String> response = mock(HttpResponse.class);
        when(http.send(any(), any(HttpResponse.BodyHandler.class))).thenReturn(response);
        var mail = new NcpMail(env, http);
        var message = new EmailController.Message(UUID.randomUUID(), UUID.randomUUID(), "p-" + "a".repeat(32),
            "user@example.invalid", "Account verification", "token link", "<a>token link</a>");
        assertTrue(mail.ready());
        when(response.statusCode()).thenReturn(201);
        when(response.body()).thenReturn("{\"requestId\":\"request-one\",\"count\":1}");
        assertEquals(new NcpMail.Outcome("ACCEPTED", "request-one"), mail.send(message));
        var request = ArgumentCaptor.forClass(HttpRequest.class);
        verify(http).send(request.capture(), any(HttpResponse.BodyHandler.class));
        assertEquals("https://mail.apigw.ntruss.com/api/v1/mails", request.getValue().uri().toString());
        String timestamp = request.getValue().headers().firstValue("x-ncp-apigw-timestamp").orElseThrow();
        assertEquals(NcpMail.signature("POST", "/api/v1/mails", timestamp, "access", "secret"),
            request.getValue().headers().firstValue("x-ncp-apigw-signature-v2").orElseThrow());
        when(response.body()).thenReturn("{\"requestId\":\"request-one\",\"count\":2}");
        assertEquals("UNKNOWN", mail.send(message).state());
        when(response.statusCode()).thenReturn(401);
        assertEquals("FAILED", mail.send(message).state());
        when(response.statusCode()).thenReturn(503);
        assertEquals("UNKNOWN", mail.send(message).state());
        when(http.send(any(), any(HttpResponse.BodyHandler.class))).thenThrow(new java.net.http.HttpTimeoutException("private token"));
        assertEquals("UNKNOWN", mail.send(message).state());
        verify(http, times(5)).send(any(), any(HttpResponse.BodyHandler.class));
        assertFalse(new NcpMail(new MockEnvironment(), http).ready());
        assertThrows(IllegalArgumentException.class, () -> new NcpMail(env.withProperty("NCP_MAIL_API_ENDPOINT", "http://untrusted.invalid"), http));
    }
}
