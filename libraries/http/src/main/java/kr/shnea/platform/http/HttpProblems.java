package kr.shnea.platform.http;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import org.slf4j.MDC;
import org.springframework.http.*;
import tools.jackson.databind.json.JsonMapper;

/** HTTP representation only. Business codes and recovery policies belong to each service. */
public final class HttpProblems {
    private HttpProblems() {}
    public static ProblemDetail body(String code, int status, String detail, HttpServletRequest request) {
        String id = RequestTrace.id(request);
        var body = ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(status), detail);
        body.setType(URI.create("urn:shnea:platform:error:" + code));
        body.setTitle(switch (status) {
            case 401 -> "인증 필요";
            case 403 -> "접근 거부";
            case 404 -> "대상 없음";
            case 409 -> "현재 상태와 충돌";
            default -> status >= 500 ? "서비스 처리 오류" : "요청 오류";
        });
        body.setInstance(URI.create("urn:shnea:platform:request:" + id));
        body.setProperty("code", code);
        body.setProperty("requestId", id);
        MDC.put("errorCode", code);
        return body;
    }
    public static HttpHeaders headers(HttpServletRequest request) {
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        headers.setCacheControl("no-store");
        headers.set("Content-Language", "ko");
        headers.set(RequestTrace.HEADER, RequestTrace.id(request));
        return headers;
    }
    public static void write(String code, int status, String detail, HttpServletRequest request,
            HttpServletResponse response, JsonMapper json) throws IOException {
        response.setStatus(status);
        response.setCharacterEncoding("UTF-8");
        headers(request).forEach((name, values) -> response.setHeader(name, values.getFirst()));
        // Explicit top-level extensions also work with standalone test mappers without Spring mix-ins.
        var problem = body(code, status, detail, request);
        var fields = new java.util.LinkedHashMap<String, Object>();
        fields.put("type", problem.getType().toString()); fields.put("title", problem.getTitle());
        fields.put("status", status); fields.put("detail", detail);
        fields.put("instance", problem.getInstance().toString()); fields.putAll(problem.getProperties());
        response.getWriter().write(json.writeValueAsString(fields));
    }
}
