package kr.shnea.platform.project;

import kr.shnea.platform.http.RequestTrace;

import org.springframework.dao.DataIntegrityViolationException;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
class ApiErrors extends ResponseEntityExceptionHandler {
    @ExceptionHandler(org.springframework.web.client.RestClientException.class)
    ResponseEntity<Object> upstreamUnavailable(Exception error, WebRequest request) {
        return problem(ApiCode.UPSTREAM_UNAVAILABLE, 502, new HttpHeaders(), request, List.of());
    }
    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<Object> conflict(Exception error, WebRequest request) {
        return problem(ApiCode.RESOURCE_CONFLICT, 409, new HttpHeaders(), request, List.of());
    }
    @ExceptionHandler(Exception.class)
    ResponseEntity<Object> unexpected(Exception error, WebRequest request) {
        // Exception messages and causes can contain SQL, credentials or provider response bodies.
        org.slf4j.LoggerFactory.getLogger(ApiErrors.class).error("api_unexpected_error class={}", error.getClass().getName());
        return problem(ApiCode.INTERNAL_ERROR, 500, new HttpHeaders(), request, List.of());
    }

    @Override protected ResponseEntity<Object> handleExceptionInternal(Exception error, Object body, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        ApiCode code = error instanceof ApiCode.Failure failure ? failure.code : ApiCode.forStatus(status.value());
        List<Map<String, String>> errors = List.of();
        if (error instanceof MethodArgumentNotValidException invalid) {
            code = ApiCode.VALIDATION_FAILED;
            errors = invalid.getBindingResult().getFieldErrors().stream().limit(50)
                .map(field -> Map.of("field", field.getField(), "code", "INVALID_VALUE",
                    "message", validationMessage(field.getCode())))
                .distinct().toList();
        }
        return problem(code, status.value(), headers, request, errors);
    }

    private ResponseEntity<Object> problem(ApiCode code, int status, HttpHeaders original, WebRequest web,
            List<Map<String, String>> errors) {
        var request = ((ServletWebRequest) web).getRequest();
        var body = ApiProblems.body(code, status, request);
        if (!errors.isEmpty()) body.setProperty("errors", errors);
        var headers = new HttpHeaders();
        headers.putAll(original); // Preserve protocol headers such as Allow and Accept.
        headers.setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        headers.setCacheControl("no-store");
        headers.set("Content-Language", "ko");
        headers.set(RequestTrace.HEADER, RequestTrace.id(request));
        return new ResponseEntity<>(body, headers, HttpStatusCode.valueOf(status));
    }

    private static String validationMessage(String code) {
        return switch (code == null ? "" : code) {
            case "NotNull", "NotBlank", "NotEmpty" -> "필수 항목을 입력해 주세요.";
            case "Size" -> "허용된 길이 또는 개수에 맞게 입력해 주세요.";
            case "Min", "Max" -> "허용된 범위의 숫자를 입력해 주세요.";
            case "Future" -> "현재 이후의 날짜와 시간을 입력해 주세요.";
            case "Pattern" -> "안내된 형식에 맞게 입력해 주세요.";
            default -> "입력값을 확인해 주세요.";
        };
    }
}
