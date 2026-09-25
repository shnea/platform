package kr.shnea.platform.project;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.UUID;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
class RequestTrace extends OncePerRequestFilter {
    static final String HEADER = "X-Request-ID";
    static final String ATTRIBUTE = RequestTrace.class.getName() + ".id";

    static String id(HttpServletRequest request) {
        if (request.getAttribute(ATTRIBUTE) instanceof String id) return id;
        String id = UUID.randomUUID().toString().replace("-", "");
        request.setAttribute(ATTRIBUTE, id);
        return id;
    }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        // Nginx overwrites caller IDs. Direct internal callers may only supply a bounded opaque ID.
        var values = Collections.list(request.getHeaders(HEADER));
        if (values.size() == 1 && values.getFirst().matches("[a-f0-9]{32}"))
            request.setAttribute(ATTRIBUTE, values.getFirst());
        String id = id(request);
        response.setHeader(HEADER, id);
        var previous = MDC.getCopyOfContextMap();
        long start = System.nanoTime();
        MDC.put("requestId", id);
        MDC.remove("errorCode");
        try {
            chain.doFilter(request, response);
        } finally {
            // Only the mapped template is logged: no query, raw path, headers, body or exception message.
            if (request.getRequestURI().startsWith("/api/v1/") || request.getRequestURI().startsWith("/internal/v1/")) {
                Object route = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
                LoggerFactory.getLogger(RequestTrace.class).info("api_request method={} route={} status={} durationMs={}",
                    request.getMethod(), route == null ? "unmatched" : route, response.getStatus(),
                    (System.nanoTime() - start) / 1_000_000);
            }
            if (previous == null) MDC.clear(); else MDC.setContextMap(previous);
        }
    }
}
