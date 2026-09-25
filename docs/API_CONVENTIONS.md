# API 오류와 요청 추적 지침

## 적용 범위

프로젝트 서비스의 관리자·연동/내부 API와 알림 서비스의 내부 이메일 API에 적용했다. 성공 응답 구조와 HTTP 상태, 관리자 JWT·서버 API 키의 권한 경계는 유지한다. 관리자 화면에서는 서버의 한국어 오류 안내와 요청 ID를 확인한다.

Nginx의 `/api/v1/`에서 자체 생성한 413/502/503/504도 표준 본문을 반환한다. 업스트림 서비스가 반환한 오류 본문은 유지한다. `/auth/`의 표준 인증 오류와 프록시의 다른 경로는 바꾸지 않는다. 관리자 웹은 표준 본문이 없는 응답에도 한국어 기본 안내를 제공한다. 아직 업무 API가 없는 파일 서비스와 향후 API는 구현 시 같은 계약을 적용한다. 개발자 센터 화면은 후속 작업이다.

현재 외부 API의 [OpenAPI 3.1.1 명세](../services/project-service/src/main/resources/openapi.json)는 관리자 JWT로 `GET /api/v1/admin/openapi`에서 조회한다. DEV 25개 경로/30개 작업을 문서화하며 PROD에서는 개발 전용 5개 작업/경로를 제거한다. 내부 이메일 API와 Keycloak OAuth/OIDC는 노출하지 않는다. 인증·본문·응답·필드 조건·부분 실패와 재시도 주의점을 명시했고 Gradle 검사에서 경로/모델 변경을 대조한다. [OpenAPI 공식 규격](https://spec.openapis.org/oas/v3.1.1.html)을 기준으로 검증한다.

Keycloak OAuth/OIDC 오류와 `/api/v1/dev/login`의 의도된 모의 로그인 결과는 기존 계약을 유지한다. 관리자 모의 로그인은 HTTP 200 안에 `httpStatus`와 `result`를 반환하며 업무 API 예외와 구분한다.

## 오류 응답

[RFC 9457](https://www.rfc-editor.org/rfc/rfc9457.html)과 Spring의 [ProblemDetail 처리](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-ann-rest-exceptions.html)를 사용한다. 오류 처리 라이브러리는 추가하지 않는다.

```http
HTTP/1.1 409 Conflict
Content-Type: application/problem+json
Content-Language: ko
Cache-Control: no-store
X-Request-ID: 0123456789abcdef0123456789abcdef
```

```json
{
  "type": "urn:shnea:platform:error:MOCK_RESET_CHANGED",
  "title": "현재 상태와 충돌",
  "status": 409,
  "detail": "초기화 대상이나 계정 상태가 변경되었습니다. 대상을 다시 확인해 주세요.",
  "instance": "urn:shnea:platform:request:0123456789abcdef0123456789abcdef",
  "code": "MOCK_RESET_CHANGED",
  "requestId": "0123456789abcdef0123456789abcdef"
}
```

| 필드 | 계약 |
| --- | --- |
| `type` | 오류 유형의 고정 URI. `urn:shnea:platform:error:{code}`이며 웹 페이지 주소가 아님 |
| `title` | HTTP 상태 범주에 대한 짧은 한국어 제목 |
| `status` | 실제 HTTP 상태와 같은 정수 |
| `detail` | 사용자용 한국어 안내. 프로그램 분기에 사용하지 않음 |
| `instance` | 요청 식별 URN. 사용자 입력 경로나 쿼리를 포함하지 않음 |
| `code` | 고정 영문 대문자 코드. 의미를 바꾸거나 다른 오류에 재사용하지 않음 |
| `requestId` | 응답 헤더와 같은 소문자 16진수 32자리 |
| `errors` | 본문 필드 검증 실패 시 `{field, code, message}` 배열. 입력 원문 미포함 |

필드 오류는 최대 50건을 제공한다. `field`는 `redirectUris[0]` 같은 입력 경로, `code`는 현재 `INVALID_VALUE`, `message`는 필수·형식·길이·범위·미래 날짜 조건에 대한 한국어 안내다. 잘못된 JSON과 경로/쿼리 형식 오류는 `INVALID_REQUEST`로 반환한다. 내부 예외·SQL·제공자 본문·거부된 값은 반환하지 않는다.

클라이언트는 HTTP 상태와 `code`를 함께 확인한다. 추가 필드는 무시하고 알 수 없는 코드나 비표준 응답도 일반 오류로 처리한다. 메시지는 HTML로 실행하지 않는다. `Allow` 등 HTTP 의미가 있는 헤더는 보존하며 인증 필터의 401은 `WWW-Authenticate: Bearer`를 제공한다.

## 복구·재시도

- 400: 입력을 수정한다. 401: 로그인 또는 키 만료·폐기·프로젝트 상태를 확인한다. 403: 권한과 환경을 확인한다.
- 409: 최신 상태·초기화 대상을 다시 읽고 사용자가 작업을 재판단한다. 같은 입력을 즉시 반복하지 않는다.
- 502/503/500: 외부 작업이 일부 반영됐을 수 있으므로 현재 상태를 먼저 조회한다. 생성·삭제·발송을 HTTP 상태만 보고 자동 반복하지 않는다.
- 기존 보호 조건·세션 종료 순서·부분 실패·감사 정책은 [프로젝트 API](PROJECT_API.md)를 따른다. 이 변경은 작업의 원자성이나 멱등성을 새로 보장하지 않는다.

## 요청 ID와 로그

Nginx가 `/api/v1/` 요청마다 `$request_id`를 생성해 호출자의 `X-Request-ID`를 덮어쓴다. 업스트림 응답의 동일 헤더는 숨기고 Nginx의 ID 하나만 응답한다. 프로젝트 서비스는 내부의 단일 32자리 소문자 16진수만 받아들이며 없거나 잘못됐거나 중복된 헤더면 새 ID를 만든다. 내부 서비스 포트는 공개하지 않는다.

프로젝트 서비스는 ID를 요청 속성과 MDC에 설정하고 응답 헤더·오류 본문·ECS 로그에 포함한다. `api_request` 로그에는 메서드·매핑된 라우트 템플릿·상태·소요 시간(ms)을 남기고 종료 후 MDC를 복원한다. 알 수 없는 경로는 `unmatched`로 기록한다. 쿼리·원문 경로·본문·토큰·비밀번호·API 키는 이 로그에 기록하지 않는다. 예상하지 못한 예외는 메시지/원인/스택 없이 클래스만 기록한다.

요청 ID는 상관관계를 찾는 값이며 인증 정보·멱등성 키가 아니다. Nginx→프로젝트와 프로젝트↔알림 내부 호출에 적용했다. 외부 NCP에는 내부 ID를 전송하지 않는다. 감사 DB·Keycloak·비동기 작업의 연계는 후속 범위다. 오류 직렬화·요청 ID 필터/전달은 `libraries:http`를 두 서비스가 공유하며 업무 코드·인증 정책·DB 소유권은 각 서비스에 남긴다.

Nginx API 경로는 쿼리가 포함될 수 있는 일반 프록시 오류 로그를 억제하고 구조화 서비스 로그와 응답 ID로 진단한다. Nginx API 이외의 경로·실제 IP 신뢰 설정은 유지한다. 프록시 자체 오류를 지표로 집계하는 기능은 모니터링 단계에서 연결한다.

## 검증

```powershell
docker compose -f compose.yml -f compose.dev.yml build project-service
docker compose -f compose.yml -f compose.dev.yml --profile test run --rm admin-check npm test
docker compose -f compose.yml -f compose.dev.yml --profile test run --rm project-check python /checks/check-api-contract.py
docker compose -f compose.yml -f compose.dev.yml --profile test run --build --rm api-check
docker run --rm --network none --volume "${PWD}/scripts:/checks:ro" --entrypoint sh register.shnea.kr/platform-nginx:0.1.0-dev /checks/check-gateway-errors.sh
```

서버 빌드는 기존 단위 검사와 오류 계약 검사를 실행한다. 통합 검사는 개발 모드의 실제 Nginx·인증·업무 API를 사용하며 프로젝트/회원 데이터는 변경하지 않는다. 자체 관리자 세션은 종료한다. 잘못된 입력·인증·권한·404·405·415·외부 요청 ID 덮어쓰기와 OAuth 오류 보존을 확인한다. 409·502·500의 원문 배제와 MDC 정리는 단위 검사로 확인한다.

`api-check`는 개발 전용 일회성 검사 이미지다. `openapi-spec-validator`로 명세를 검증하고 실제 조회 결과를 JSON Schema로 대조한다. 기존 READY DEV 환경이 하나 필요하며 발송/회원 변경을 하지 않는다. 없는 환경으로 이메일 컨텍스트 조회를 실패시켜 외부 발송 전에 차단되는 역방향 호출도 검증한다. 게이트웨이 검사는 격리 컨테이너 안에서 실제 413·연결 실패 502·무응답 504와 서비스 자체 503 본문 보존을 확인한다.

알림 전용 코드: `INVALID_EMAIL_REQUEST`(400), `EMAIL_ACCESS_DENIED`(403), `EMAIL_REQUEST_CONFLICT`(409), `EMAIL_RATE_LIMITED`(429), `EMAIL_CONTEXT_UNAVAILABLE`(503), `EMAIL_DELIVERY_UNCONFIRMED`(503). 전달 결과 불명확 시 자동 재발송 금지는 유지한다. 게이트웨이는 공통 코드 외 `UPSTREAM_TIMEOUT`(504)을 사용한다.

## 오류 코드 목록

기준 구현은 [`ApiCode.java`](../services/project-service/src/main/java/kr/shnea/platform/project/ApiCode.java)다. 표와 실제 구현은 함께 갱신한다. 한국어 문구는 개선할 수 있지만 코드의 의미·HTTP 상태 변경은 API 호환성 검토 대상이다.

| ?? | HTTP | ?? |
| --- | --- | --- |
| `INVALID_REQUEST` | 400 | 요청 형식과 입력값을 확인해 주세요. |
| `VALIDATION_FAILED` | 400 | 입력 조건에 맞지 않는 항목을 확인해 주세요. |
| `AUTHENTICATION_REQUIRED` | 401 | 인증이 필요합니다. 다시 로그인해 주세요. |
| `INVALID_API_KEY` | 401 | API 키가 유효하지 않습니다. 만료·폐기 여부와 프로젝트 상태를 확인해 주세요. |
| `ACCESS_DENIED` | 403 | 이 요청을 처리할 권한이 없습니다. |
| `INSUFFICIENT_SCOPE` | 403 | API 키에 필요한 기능 권한이 없습니다. |
| `DEV_ENVIRONMENT_REQUIRED` | 403 | 개발 모드의 DEV 환경에서만 사용할 수 있습니다. |
| `MOCK_USER_DISABLED` | 403 | 테스트 계정의 로그인이 차단되어 있습니다. |
| `RESOURCE_NOT_FOUND` | 404 | 대상을 찾을 수 없습니다. 목록을 새로고침해 주세요. |
| `METHOD_NOT_ALLOWED` | 405 | 지원하지 않는 요청 방식입니다. |
| `NOT_ACCEPTABLE` | 406 | 요청한 응답 형식을 제공할 수 없습니다. |
| `RESOURCE_CONFLICT` | 409 | 이미 있는 항목이거나 다른 데이터와 충돌합니다. 현재 상태를 확인해 주세요. |
| `ENVIRONMENT_NOT_READY` | 409 | 환경 설정이 아직 반영되지 않았습니다. 환경 상태를 확인해 주세요. |
| `PROJECT_SUSPENDED` | 409 | 중지된 프로젝트입니다. 프로젝트 상태를 확인해 주세요. |
| `SETTINGS_CHANGED` | 409 | 설정이 변경되었습니다. 새로고침 후 다시 확인해 주세요. |
| `MEMBER_STATE_CHANGED` | 409 | 회원 상태가 변경되었습니다. 새로고침 후 다시 확인해 주세요. |
| `MOCK_RESET_CHANGED` | 409 | 초기화 대상이나 계정 상태가 변경되었습니다. 대상을 다시 확인해 주세요. |
| `MOCK_RESET_PROTECTION` | 409 | 테스트 계정 보호 설정을 확인해야 합니다. 관리자에게 문의해 주세요. |
| `REALM_OWNERSHIP_MISMATCH` | 409 | 인증 영역의 소유 설정이 일치하지 않습니다. 관리자에게 문의해 주세요. |
| `AUTHENTICATION_POLICY_REVIEW` | 409 | 별도 비밀번호 규칙이나 이메일 중복 정책을 먼저 확인해 주세요. |
| `SOCIAL_PROVIDER_CONFLICT` | 409 | 소셜 제공자 설정을 확인해야 합니다. 새로고침 후 관리자에게 문의해 주세요. |
| `PAYLOAD_TOO_LARGE` | 413 | 요청 크기가 허용 범위를 초과했습니다. |
| `UNSUPPORTED_MEDIA_TYPE` | 415 | 지원하지 않는 요청 데이터 형식입니다. |
| `RATE_LIMITED` | 429 | 요청이 너무 많습니다. 잠시 후 다시 시도해 주세요. |
| `INTERNAL_ERROR` | 500 | 요청 처리 중 오류가 발생했습니다. 현재 상태를 확인하고 요청 ID로 문의해 주세요. |
| `UPSTREAM_UNAVAILABLE` | 502 | 연결된 서비스의 처리를 확인하지 못했습니다. 현재 상태를 먼저 확인해 주세요. |
| `SERVICE_UNAVAILABLE` | 503 | 서비스를 일시적으로 사용할 수 없습니다. 잠시 후 상태를 확인해 주세요. |
| `INVALID_PAGINATION` | 400 | 조회 개수는 1~100, 시작 위치는 0 이상으로 입력해 주세요. |
| `INVALID_CREDENTIAL_SCOPES` | 400 | 사용 가능한 기능 권한을 중복 없이 하나 이상 선택해 주세요. |
| `INVALID_EXPIRY` | 400 | 만료일은 현재 이후로 지정해 주세요. |
| `INVALID_PROJECT_STATUS` | 400 | 프로젝트 상태는 ACTIVE 또는 SUSPENDED로 지정해 주세요. |
| `INVALID_MEMBER_SEARCH` | 400 | 검색어는 제어문자 없이 200자 이내, 조회 개수는 1~100, 시작 위치는 0~1000000으로 입력해 주세요. |
| `INVALID_SESSION_ID` | 400 | 세션 식별자를 확인해 주세요. 영문·숫자·밑줄·하이픈을 최대 128자까지 사용할 수 있습니다. |
| `INVALID_REDIRECT` | 400 | 정확한 HTTPS 콜백 주소를 입력해 주세요. DEV 환경에서는 HTTP localhost 주소도 허용합니다. |
| `INVALID_ENVIRONMENT` | 400 | DEV 또는 PROD 환경과 1~10개의 콜백 주소를 지정해 주세요. |
| `INVALID_MOCK_SCENARIO` | 400 | 지원하지 않는 테스트 시나리오입니다. |
| `UNKNOWN_SOCIAL_PROVIDER` | 400 | 지원하지 않는 소셜 제공자입니다. |
| `SOCIAL_CREDENTIALS_MISSING` | 400 | 공통 소셜 인증 키가 준비되지 않았습니다. 서버 설정을 확인해 주세요. |
| `SOCIAL_PRODUCTION_REQUIRED` | 400 | 실제 소셜 로그인은 운영 모드의 PROD 환경에서만 켤 수 있습니다. |
| `EMAIL_DELIVERY_NOT_READY` | 400 | 환경에 맞는 이메일 전달 경로가 준비되지 않았습니다. 발송 설정을 확인해 주세요. |
