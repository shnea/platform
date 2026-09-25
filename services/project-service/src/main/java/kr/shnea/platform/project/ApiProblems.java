package kr.shnea.platform.project;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import kr.shnea.platform.http.HttpProblems;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
class ApiProblems {
    private final JsonMapper json;
    ApiProblems(JsonMapper json) { this.json = json; }

    static ProblemDetail body(ApiCode code, int status, HttpServletRequest request) {
        return HttpProblems.body(code.name(), status, code.detail, request);
    }

    void write(ApiCode code, HttpServletRequest request, HttpServletResponse response) throws IOException {
        HttpProblems.write(code.name(), code.status, code.detail, request, response, json);
    }
}
