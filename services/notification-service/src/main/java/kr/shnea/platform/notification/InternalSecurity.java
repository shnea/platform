package kr.shnea.platform.notification;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@org.springframework.context.annotation.Import(kr.shnea.platform.http.RequestTrace.class)
class InternalSecurity extends OncePerRequestFilter {
    private final byte[] secret;
    private final byte[] eventsSecret;
    private final tools.jackson.databind.json.JsonMapper json;
    InternalSecurity(@Value("${PLATFORM_MAIL_SECRET:}") String secret,
            @Value("${PLATFORM_EVENTS_SECRET:}") String eventsSecret, tools.jackson.databind.json.JsonMapper json) {
        if (secret.length() < 32) throw new IllegalArgumentException("Configure PLATFORM_MAIL_SECRET (32+ characters)");
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        if (!eventsSecret.isEmpty() && eventsSecret.length()<32) throw new IllegalArgumentException("Configure PLATFORM_EVENTS_SECRET (32+ characters)");
        this.eventsSecret=eventsSecret.getBytes(StandardCharsets.UTF_8);
        this.json = json;
    }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        String path = request.getRequestURI();
        if (path.equals("/internal/v1/monitoring")) { chain.doFilter(request,response); return; } // Controller validates monitoring secret.
        if (path.startsWith("/actuator/health")) { chain.doFilter(request, response); return; }
        if (path.equals("/internal/v1/events/jobs") || path.equals("/internal/v1/operational-alerts") ||
                path.equals("/internal/v1/operational-alerts/email-settings") || path.equals("/internal/v1/operational-alerts/email-deliveries") ||
                path.matches("/internal/v1/operational-alerts/[a-fA-F0-9-]{36}/acknowledge")) {
            String provided=request.getHeader("X-Platform-Event-Key");
            if (eventsSecret.length<32 || provided==null || !MessageDigest.isEqual(eventsSecret,provided.getBytes(StandardCharsets.UTF_8))) {
                kr.shnea.platform.http.HttpProblems.write("EVENT_ACCESS_DENIED",403,"이벤트 내부 API에 접근할 권한이 없습니다.",request,response,json);
                return;
            }
            response.setHeader("Cache-Control","no-store");
            chain.doFilter(request,response); return;
        }
        String provided = request.getHeader("X-Platform-Mail-Key");
        if (!path.startsWith("/internal/v1/email") || provided == null ||
                !MessageDigest.isEqual(secret, provided.getBytes(StandardCharsets.UTF_8))) {
            kr.shnea.platform.http.HttpProblems.write("EMAIL_ACCESS_DENIED", 403,
                "이메일 내부 API에 접근할 권한이 없습니다.", request, response, json);
            return;
        }
        response.setHeader("Cache-Control", "no-store");
        chain.doFilter(request, response);
    }
}
