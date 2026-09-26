package kr.shnea.platform.notification;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.RestClientException;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import kr.shnea.platform.http.HttpProblems;

@RestControllerAdvice
class EmailErrors extends ResponseEntityExceptionHandler {
    @ExceptionHandler(RestClientException.class)
    ResponseEntity<Object> unavailable(Exception error, WebRequest request) {
        return response("EMAIL_CONTEXT_UNAVAILABLE", 503, "이메일 발송 환경을 확인하지 못했습니다.", request, new HttpHeaders(), null);
    }
    @ExceptionHandler(Exception.class)
    ResponseEntity<Object> unexpected(Exception error, WebRequest request) {
        org.slf4j.LoggerFactory.getLogger(EmailErrors.class).error("api_unexpected_error class={}", error.getClass().getName());
        return response("INTERNAL_ERROR", 500, "요청 처리 중 오류가 발생했습니다. 요청 ID로 문의해 주세요.", request, new HttpHeaders(), null);
    }
    @Override protected ResponseEntity<Object> handleExceptionInternal(Exception error, Object body, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        String[] message = switch (status.value()) {
            case 403 -> new String[]{"EMAIL_ACCESS_DENIED", "이메일 발송 권한과 환경을 확인해 주세요."};
            case 404 -> new String[]{"RESOURCE_NOT_FOUND", "대상을 찾을 수 없습니다."};
            case 405 -> new String[]{"METHOD_NOT_ALLOWED", "지원하지 않는 요청 방식입니다."};
            case 406 -> new String[]{"NOT_ACCEPTABLE", "요청한 응답 형식을 제공할 수 없습니다."};
            case 409 -> new String[]{"EMAIL_REQUEST_CONFLICT", "같은 요청 ID에 다른 이메일 내용이 지정되었습니다."};
            case 413 -> new String[]{"PAYLOAD_TOO_LARGE", "요청 크기가 허용 범위를 초과했습니다."};
            case 415 -> new String[]{"UNSUPPORTED_MEDIA_TYPE", "지원하지 않는 요청 데이터 형식입니다."};
            case 429 -> new String[]{"EMAIL_RATE_LIMITED", "이메일 발송 요청이 너무 많습니다. 잠시 후 다시 확인해 주세요."};
            case 503 -> new String[]{"EMAIL_DELIVERY_UNCONFIRMED", "이메일 전달 결과를 확인하지 못했습니다. 중복 발송을 피하려면 상태를 먼저 확인해 주세요."};
            default -> status.is5xxServerError() ? new String[]{"INTERNAL_ERROR", "이메일 처리 중 오류가 발생했습니다."}
                : new String[]{"INVALID_EMAIL_REQUEST", "이메일 요청 형식과 입력 조건을 확인해 주세요."};
        };
        if (((ServletWebRequest)request).getRequest().getRequestURI().startsWith("/internal/v1/events/")) {
            message=switch(status.value()) {
                case 409 -> new String[]{"EVENT_CONFLICT","같은 이벤트 또는 작업 ID에 다른 내용이 지정되었습니다."};
                case 422 -> new String[]{"EVENT_VERSION_UNSUPPORTED","지원하지 않는 이벤트 버전입니다."};
                default -> status.is5xxServerError() ? new String[]{"INTERNAL_ERROR","이벤트 처리 중 오류가 발생했습니다."}
                    : new String[]{"INVALID_EVENT","이벤트 형식과 입력 조건을 확인해 주세요."};
            };
        }
        if (((ServletWebRequest)request).getRequest().getRequestURI().startsWith("/internal/v1/operational-alerts")) {
            message=switch(status.value()) {
                case 409 -> new String[]{"SETTINGS_CHANGED","설정이 변경되었습니다. 새로고침 후 다시 확인해 주세요."};
                case 404 -> new String[]{"RESOURCE_NOT_FOUND","선택한 환경의 알림을 찾을 수 없습니다."};
                default -> status.is5xxServerError() ? new String[]{"INTERNAL_ERROR","운영 알림 처리 중 오류가 발생했습니다."}
                    : new String[]{"INVALID_REQUEST","알림 요청 형식과 입력 조건을 확인해 주세요."};
            };
        }
        return response(message[0], status.value(), message[1], request, headers, error);
    }
    private ResponseEntity<Object> response(String code, int status, String detail, WebRequest web, HttpHeaders original, Exception error) {
        var request = ((ServletWebRequest) web).getRequest();
        var body = HttpProblems.body(code, status, detail, request);
        if (error instanceof MethodArgumentNotValidException invalid)
            body.setProperty("errors", invalid.getFieldErrors().stream().limit(50).map(field ->
                Map.of("field", field.getField(), "code", "INVALID_VALUE", "message", "필수 항목과 허용 형식·길이를 확인해 주세요.")).distinct().toList());
        var headers = new HttpHeaders();
        headers.putAll(original); headers.putAll(HttpProblems.headers(request));
        return new ResponseEntity<>(body, headers, status);
    }
}
