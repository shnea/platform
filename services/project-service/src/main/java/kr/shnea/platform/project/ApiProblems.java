package kr.shnea.platform.project;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import org.slf4j.MDC;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
class ApiProblems {
    private final JsonMapper json;
    ApiProblems(JsonMapper json) { this.json = json; }

    static ProblemDetail body(ApiCode code, int status, HttpServletRequest request) {
        String id = RequestTrace.id(request);
        var body = ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(status), code.detail);
        body.setType(URI.create("urn:shnea:platform:error:" + code.name()));
        body.setTitle(switch (status) {
            case 401 -> "인증 필요";
            case 403 -> "접근 거부";
            case 404 -> "대상 없음";
            case 409 -> "현재 상태와 충돌";
            default -> status >= 500 ? "서비스 처리 오류" : "요청 오류";
        });
        body.setInstance(URI.create("urn:shnea:platform:request:" + id));
        body.setProperty("code", code.name());
        body.setProperty("requestId", id);
        MDC.put("errorCode", code.name());
        return body;
    }

    void write(ApiCode code, HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.setStatus(code.status);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Content-Language", "ko");
        response.setHeader(RequestTrace.HEADER, RequestTrace.id(request));
        response.getWriter().write(json.writeValueAsString(body(code, code.status, request)));
    }
}
