package kr.shnea.platform.project;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

// Public machine codes: keep meanings stable even when Korean wording changes.
enum ApiCode {
    AI_INVALID_REQUEST(422, "AI 요청의 필드·텍스트·모델·차원·배치 개수를 확인해 주세요."),
    AI_NOT_CONFIGURED(503, "뇌대리 AI 서버 연결 설정이 필요합니다. 관리자에게 문의해 주세요."),
    AI_UPSTREAM_AUTH_FAILED(502, "뇌대리 서버 인증 설정을 확인해야 합니다. 관리자에게 문의해 주세요."),
    AI_BUSY(429, "AI 처리 또는 공급자 요청 한도에 도달했습니다. 잠시 후 다시 시도해 주세요."),
    AI_UNAVAILABLE(503, "뇌대리 AI 처리를 확인하지 못했습니다. 연결·지원 기능·자원 상태를 확인해 주세요."),
    AI_TIMEOUT(504, "AI 처리 제한시간을 초과했습니다. 자동으로 재실행하지 않습니다."),
    AI_INVALID_RESPONSE(502, "AI 응답이 요청한 결과 계약과 일치하지 않습니다. 관리자에게 문의해 주세요."),
    AI_DELIVERY_NOT_CONFIGURED(503, "AI 완료 알림의 서명 설정이 필요합니다. 관리자에게 문의해 주세요."),
    AI_EVENT_SIGNATURE_INVALID(401, "AI 완료 알림의 서명과 전송 시각을 확인해 주세요."),
    AI_EVENT_INVALID(422, "AI 완료 알림의 형식과 요청 범위를 확인해 주세요."),
    AI_EVENT_CONFLICT(409, "같은 AI 완료 알림 ID 또는 작업의 내용이 다릅니다."),
    EXTERNAL_JOB_REQUEST_CONFLICT(409, "같은 요청 ID로 다른 작업을 등록할 수 없습니다."),
    EXTERNAL_JOB_LEASE_LOST(409, "작업 점유가 만료되거나 다른 워커로 변경되었습니다. 실행 결과를 다시 반영하지 마세요."),
    EXTERNAL_JOB_CAPACITY(429, "이 환경의 작업 보관 또는 활성 작업 한도에 도달했습니다."),
    LOG_QUOTA_EXCEEDED(429, "환경의 로그 전송 한도에 도달했습니다. 전송량을 줄여 주세요."),
    LOG_BACKEND_UNAVAILABLE(503, "로그 저장소 응답을 확인하지 못했습니다. 제한된 횟수로 재시도해 주세요."),
    FILE_SERVICE_DISABLED(403, "프로젝트에서 파일 서비스를 사용하도록 설정해 주세요."),
    EVENT_NOT_RETRYABLE(409, "최종 실패한 이벤트만 다시 전달할 수 있습니다."),
    JOB_STATE_CHANGED(409, "작업 상태가 변경되었습니다. 작업 목록을 다시 확인해 주세요."),
    JOB_NOT_CANCELLABLE(409, "대기 중이거나 재시도를 기다리는 작업만 취소할 수 있습니다."),
    JOB_NOT_RETRYABLE(409, "최종 실패한 작업만 다시 시도할 수 있습니다."),
    INVALID_REQUEST(400, "요청 형식과 입력값을 확인해 주세요."),
    VALIDATION_FAILED(400, "입력 조건에 맞지 않는 항목을 확인해 주세요."),
    AUTHENTICATION_REQUIRED(401, "인증이 필요합니다. 다시 로그인해 주세요."),
    INVALID_API_KEY(401, "API 키가 유효하지 않습니다. 만료·폐기 여부와 프로젝트 상태를 확인해 주세요."),
    ACCESS_DENIED(403, "이 요청을 처리할 권한이 없습니다."),
    INSUFFICIENT_SCOPE(403, "API 키에 필요한 기능 권한이 없습니다."),
    DEV_ENVIRONMENT_REQUIRED(403, "개발 모드의 DEV 환경에서만 사용할 수 있습니다."),
    MOCK_USER_DISABLED(403, "테스트 계정의 로그인이 차단되어 있습니다."),
    RESOURCE_NOT_FOUND(404, "대상을 찾을 수 없습니다. 목록을 새로고침해 주세요."),
    AI_JOB_NOT_FOUND(404, "이 환경에서 AI 작업을 찾을 수 없습니다."),
    AI_REQUEST_CONFLICT(409, "같은 AI 요청 ID의 내용을 변경할 수 없습니다. 새 작업에는 새 요청 ID를 사용하세요."),
    AI_REQUEST_UNCONFIRMED(409, "AI 요청의 접수 여부를 확인하지 못했습니다. 작업 목록과 요청 ID를 확인하세요. 자동 재실행하지 않습니다."),
    AI_REQUEST_CAPACITY(409, "이 환경의 AI 요청 이력 한도에 도달했습니다. 운영자에게 문의해 주세요."),
    AI_RESULT_EXPIRED(410, "AI 결과의 보관 기간이 지났습니다. 기존 결과를 다시 요청하지 마세요."),
    AI_RESULT_RECEIVED(410, "이미 저장 완료를 확인한 AI 결과입니다. 호스트에 저장한 결과를 사용하세요."),
    METHOD_NOT_ALLOWED(405, "지원하지 않는 요청 방식입니다."),
    NOT_ACCEPTABLE(406, "요청한 응답 형식을 제공할 수 없습니다."),
    RESOURCE_CONFLICT(409, "이미 있는 항목이거나 다른 데이터와 충돌합니다. 현재 상태를 확인해 주세요."),
    ENVIRONMENT_NOT_READY(409, "환경 설정이 아직 반영되지 않았습니다. 환경 상태를 확인해 주세요."),
    PROJECT_SUSPENDED(409, "중지된 프로젝트입니다. 프로젝트 상태를 확인해 주세요."),
    SETTINGS_CHANGED(409, "설정이 변경되었습니다. 새로고침 후 다시 확인해 주세요."),
    MEMBER_STATE_CHANGED(409, "회원 상태가 변경되었습니다. 새로고침 후 다시 확인해 주세요."),
    MOCK_RESET_CHANGED(409, "초기화 대상이나 계정 상태가 변경되었습니다. 대상을 다시 확인해 주세요."),
    MOCK_RESET_PROTECTION(409, "테스트 계정 보호 설정을 확인해야 합니다. 관리자에게 문의해 주세요."),
    REALM_OWNERSHIP_MISMATCH(409, "인증 영역의 소유 설정이 일치하지 않습니다. 관리자에게 문의해 주세요."),
    AUTHENTICATION_POLICY_REVIEW(409, "별도 비밀번호 규칙이나 이메일 중복 정책을 먼저 확인해 주세요."),
    SOCIAL_PROVIDER_CONFLICT(409, "소셜 제공자 설정을 확인해야 합니다. 새로고침 후 관리자에게 문의해 주세요."),
    PAYLOAD_TOO_LARGE(413, "요청 크기가 허용 범위를 초과했습니다."),
    UNSUPPORTED_MEDIA_TYPE(415, "지원하지 않는 요청 데이터 형식입니다."),
    RATE_LIMITED(429, "요청이 너무 많습니다. 잠시 후 다시 시도해 주세요."),
    INTERNAL_ERROR(500, "요청 처리 중 오류가 발생했습니다. 현재 상태를 확인하고 요청 ID로 문의해 주세요."),
    UPSTREAM_UNAVAILABLE(502, "연결된 서비스의 처리를 확인하지 못했습니다. 현재 상태를 먼저 확인해 주세요."),
    SERVICE_UNAVAILABLE(503, "서비스를 일시적으로 사용할 수 없습니다. 잠시 후 상태를 확인해 주세요."),
    INVALID_PAGINATION(400, "조회 개수는 1~100, 시작 위치는 0 이상으로 입력해 주세요."),
    INVALID_CREDENTIAL_SCOPES(400, "사용 가능한 기능 권한을 중복 없이 하나 이상 선택해 주세요."),
    INVALID_EXPIRY(400, "만료일은 현재 이후로 지정해 주세요."),
    INVALID_PROJECT_STATUS(400, "프로젝트 상태는 ACTIVE 또는 SUSPENDED로 지정해 주세요."),
    INVALID_MEMBER_SEARCH(400, "검색어는 제어문자 없이 200자 이내, 조회 개수는 1~100, 시작 위치는 0~1000000으로 입력해 주세요."),
    INVALID_SESSION_ID(400, "세션 식별자를 확인해 주세요. 영문·숫자·밑줄·하이픈을 최대 128자까지 사용할 수 있습니다."),
    INVALID_REDIRECT(400, "정확한 HTTPS 콜백 주소를 입력해 주세요. DEV 환경에서는 HTTP localhost 주소도 허용합니다."),
    INVALID_ENVIRONMENT(400, "DEV 또는 PROD 환경과 1~10개의 콜백 주소를 지정해 주세요."),
    INVALID_MOCK_SCENARIO(400, "지원하지 않는 테스트 시나리오입니다."),
    UNKNOWN_SOCIAL_PROVIDER(400, "지원하지 않는 소셜 제공자입니다."),
    SOCIAL_CREDENTIALS_MISSING(400, "공통 소셜 인증 키가 준비되지 않았습니다. 서버 설정을 확인해 주세요."),
    SOCIAL_PRODUCTION_REQUIRED(400, "실제 소셜 로그인은 운영 모드의 PROD 환경에서만 켤 수 있습니다."),
    EMAIL_DELIVERY_NOT_READY(400, "환경에 맞는 이메일 전달 경로가 준비되지 않았습니다. 발송 설정을 확인해 주세요.");

    final int status;
    final String detail;
    ApiCode(int status, String detail) { this.status = status; this.detail = detail; }
    Failure failure() { return new Failure(this); }
    Failure beforeDispatch() { return new Failure(this, false); }

    static ApiCode forStatus(int status) {
        return switch (status) {
            case 400 -> INVALID_REQUEST;
            case 401 -> AUTHENTICATION_REQUIRED;
            case 403 -> ACCESS_DENIED;
            case 404 -> RESOURCE_NOT_FOUND;
            case 405 -> METHOD_NOT_ALLOWED;
            case 406 -> NOT_ACCEPTABLE;
            case 409 -> RESOURCE_CONFLICT;
            case 413 -> PAYLOAD_TOO_LARGE;
            case 415 -> UNSUPPORTED_MEDIA_TYPE;
            case 429 -> RATE_LIMITED;
            case 502 -> UPSTREAM_UNAVAILABLE;
            case 503 -> SERVICE_UNAVAILABLE;
            default -> status >= 500 ? INTERNAL_ERROR : INVALID_REQUEST;
        };
    }

    static final class Failure extends ResponseStatusException {
        final ApiCode code;
        final boolean dispatched;
        Failure(ApiCode code) { this(code, true); }
        Failure(ApiCode code, boolean dispatched) { super(HttpStatus.valueOf(code.status), code.detail); this.code = code; this.dispatched = dispatched; }
    }
}
