# AI 로그인 연결 계약

- 플랫폼: `https://platform.shnea.kr`
- 관리자가 프로젝트·환경을 만들고 정확한 호스트 콜백을 등록해야 한다. `ACTIVE` 프로젝트 + `READY` 환경을 사용한다.
- `issuer`: 관리자가 전달하거나 서버의 `GET /api/v1/integration/context` 결과에서 가져온다. 프로젝트 코드로 realm을 추측하지 않는다.
- Discovery: `{issuer}/.well-known/openid-configuration`
- Client ID: `app` (public client, client secret 없음)
- Flow: Authorization Code + PKCE S256. Scope: `openid profile email`.
- 해당 스택의 OIDC 라이브러리로 state·nonce·PKCE·서명·issuer·만료·토큰 종류별 audience를 검증한다.
- 호스트 API용 access token audience는 자동 등록되지 않는다. 별도 Bearer API를 만들면 운영자와 audience 계약을 확정한다. ID token을 업무 API 토큰으로 쓰지 않는다.
- 사용자 키는 검증한 `iss` + `sub`. 다른 환경 계정을 이메일만으로 합치지 않는다.
- Password grant는 비활성화돼 있다. `/api/v1/config`는 플랫폼 관리자 로그인 설정이므로 사용하지 않는다.
- 호스트 세션·업무 권한·CSRF·로그아웃·미저장 내용 보호는 호스트 책임이다. 브라우저에 서버 API 키를 넣지 않는다.
- 등록 콜백 origin만 Keycloak 출처 설정에 반영한다. 로그아웃 복귀 URL·confidential client는 별도 설정 여부를 확인한다.
- DEV 인증 메일은 모의 수신함, 실제 소셜 설정·키는 플랫폼 운영자가 관리한다.
- JWT 자체 검증만으로 기존 토큰의 즉시 폐기를 보장하지 않는다. 즉시 차단이 필요하면 별도 정책을 정한다.

## 서버 연결 점검

`GET /api/v1/integration/context` + `X-Platform-Key` (`integration:read`).
응답: `{projectId, environmentId, kind, issuer, scopes}`. 예상한 프로젝트·환경과 일치해야 한다.
서버 키의 인증과 이용자 로그인은 별개이며 키는 환경 전체의 기능 권한이다.

## DEV Mock

플랫폼 개발 모드 + DEV 환경 + `auth:mock` 권한에서만 `POST /api/v1/dev/login` 사용.
입력: `{provider: "kakao" | "naver" | "google", subject: "test-user", scenario: "success" | "cancelled" | "access_denied" | "provider_unavailable"}`.
운영 로그인에 사용하지 않는다. 실제 제공자 로그인·콜백 검수와 구분한다.
전체 요청/응답: https://platform.shnea.kr/integrations/project.openapi.json

## 완료 검사

성공·취소·토큰 만료·다른 환경 토큰 거부·로그아웃·재로그인 확인. 키/토큰/비밀번호를 로그·Git에 남기지 않는다.
