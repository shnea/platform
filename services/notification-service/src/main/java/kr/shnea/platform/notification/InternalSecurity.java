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
class InternalSecurity extends OncePerRequestFilter {
    private final byte[] secret;
    InternalSecurity(@Value("${PLATFORM_MAIL_SECRET:}") String secret) {
        if (secret.length() < 32) throw new IllegalArgumentException("Configure PLATFORM_MAIL_SECRET (32+ characters)");
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        String path = request.getRequestURI();
        if (path.startsWith("/actuator/health")) { chain.doFilter(request, response); return; }
        String provided = request.getHeader("X-Platform-Mail-Key");
        if (!path.startsWith("/internal/v1/email") || provided == null ||
                !MessageDigest.isEqual(secret, provided.getBytes(StandardCharsets.UTF_8))) {
            response.setStatus(403); return;
        }
        response.setHeader("Cache-Control", "no-store");
        chain.doFilter(request, response);
    }
}
