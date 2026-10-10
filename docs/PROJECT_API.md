# 프로젝트·인증 API

오류 형식·한국어 안내·고정 오류 코드·요청 ID·재시도 기준은 [API 공통 지침](API_CONVENTIONS.md)을 따른다. 기존 성공 응답과 HTTP 상태는 유지한다.

## 현재 제공 범위

AI 지원 조회·Raya·임베딩·작업 실행/조회/취소·usage는 서버 키의 `ai:read`·`ai:route`·`ai:embed`·`ai:execute`·`ai:jobs:read`·`ai:cancel`·`ai:usage` 권한으로 분리한다. 기존 키에 자동 부여하지 않는다. 플랫폼 로그인 기능을 사용하지 않고 다른 OIDC를 사용하는 호스트도 자신의 이용자를 인증·인가한 뒤 서버 키로 독립 이용한다. [AI 연결 지침](integration/AI.md)을 따른다.

프로젝트·환경 등록과 설정, Keycloak realm 생성·재시도, 프로젝트 중지·재개, 권한별 서버 API 키 발급·폐기, 감사 이력, 개발 소셜 Mock 로그인 API를 제공한다. 소셜 3종 설정·공통 콜백, 가입·복구 정책과 회원·세션 관리, 인증 이메일의 NCP 전달 경로·개발 수신함을 연결했다. 초대 가입·일반 알림 업무 API는 후속 작업이다.

## 회원·세션 관리

플랫폼 관리자 JWT 전용이며 환경 ID로 저장된 realm과 소유 표식을 확인한다. Keycloak 관리 API를 이용하며 회원 원본을 플랫폼 DB에 복제하지 않는다. 회원 ID는 현재 지원하는 Keycloak 로컬 계정의 UUID, 세션 ID는 최대 128자의 영문·숫자·밑줄·하이픈 문자열이다. 서비스 계정은 회원 조작 대상에서 제외한다.

| 메서드 | `/api/v1/admin` 이후 경로 | 동작 |
|---|---|---|
| GET | `/environments/{id}/users?search=&limit=20&offset=0` | 아이디·이름·이메일 검색, `{items, hasMore}` 반환 |
| GET | `/environments/{id}/users/{userId}` | `{user, providers}` 반환. 제공자 별칭만 공개 |
| GET | `/environments/{id}/users/{userId}/sessions` | 온라인 세션 ID·IP·로그인/최근 접근 시간(ms)·클라이언트 이름 |
| PUT | `/environments/{id}/users/{userId}/state` | `{enabled, expectedEnabled}` 필수. 성공 204 |
| DELETE | `/environments/{id}/users/{userId}/sessions/{sessionId}` | 해당 회원 소유 온라인 세션 한 개 종료, 성공 204 |
| DELETE | `/environments/{id}/users/{userId}/sessions` | 온라인·오프라인 세션 종료, 성공 204 |

`limit`은 1~100, `offset`은 0~1,000,000, 검색어는 제어문자 없이 200자 이하다. 부분 검색은 Keycloak의 검색 규칙을 사용하며 목록 한 페이지 외에 다음 행 하나만 조회한다. 목록에서는 소셜 연결·세션을 회원마다 조회하지 않는다. 비밀번호·토큰·임의 속성·소셜 계정의 외부 ID는 응답하지 않는다.

조회한 `enabled`를 `expectedEnabled`로 전송한다. 현재 값이 다르면 409이며 새로고침 후 재판단한다. 회원 상태와 프로젝트 중지/재개·개발 Mock 요청은 같은 프로젝트 잠금으로 직렬화한다. 중지된 프로젝트는 회원 활성화를 차단하고 조회·비활성화·세션 종료는 허용한다. 미반영 환경은 409, 해당 환경에 없는 회원이나 해당 회원 소유가 아닌 세션은 404다. 조회 뒤 자연 만료된 세션의 삭제는 성공으로 처리한다.

비활성화는 먼저 계정을 막은 뒤 세션을 종료한다. 세션 종료만 실패하면 계정은 비활성 상태로 유지되고 502를 반환하므로 상세를 새로고침한 다음 전체 세션 종료를 재시도한다. 전체 종료는 Keycloak 로그아웃과 해당 회원의 오프라인 세션 명시적 삭제를 사용하며 동의 설정을 취소하지 않는다. [Keycloak 관리 API](https://www.keycloak.org/docs-api/latest/rest-api/index.html)의 회원·세션 API를 사용한다.

감사 이벤트는 `user.enabled`, `user.disabled`, `user.session.ended`, `user.sessions.ended`다. 인증 서버 작업 중 거부·실패는 `.failed`로 기록한다. 기존 감사 응답에 nullable `environment_id`, `session_id`를 추가했으며 `target_id`는 회원 ID다. 입력 검증·인증 실패 등 작업 진입 전 거부는 회원 관리 이벤트를 생성하지 않는다. DB 기록 실패·프로세스 중단과 Keycloak 변경을 하나의 트랜잭션으로 보장하지 않는다. 기존 접근 토큰을 자체 검증하는 서비스는 만료까지 허용할 수 있으며 별도 즉시 차단 정책이 필요하다.

검사: `./scripts/dev.ps1 --profile test run --rm project-check python /checks/check-members.py`. 새 전용 프로젝트만 만들며 기본 종료 시 키 폐기·프로젝트 중지, 계정 보존을 수행한다. `--keep-active`는 성공한 검수 데이터를 화면 확인용으로 유지하므로 확인 후 해당 프로젝트를 중지한다.

## DEV 테스트 계정 초기화

개발 모드의 관리자 JWT 전용 API다. 서버 API 키·이용자 토큰으로 실행할 수 없으며 운영 모드에서는 컨트롤러를 등록하지 않는다. DEV·프로젝트 사용 중·환경 READY 조건을 모두 확인하고, 프로젝트 수명주기·Mock 로그인·회원 관리와 같은 프로젝트 잠금으로 직렬화한다.

| 메서드·경로 | 용도 |
| --- | --- |
| `GET /api/v1/admin/environments/{id}/mock-users/reset-preview` | 삭제할 계정 최대 20명의 `items`(id, username, provider), `hasMore`, `revision` 조회 |
| `POST /api/v1/admin/environments/{id}/mock-users/reset` | 확인한 `{userIds, revision}` 전송. `requested`, `deleted`, `failed`, 계정별 `items`(id, username, status) 반환 |

미리보기는 삭제하지 않는다. 초기화 시 서버가 대상을 다시 계산하고 목록과 revision이 다르면 변경 없이 409를 반환한다. 목록을 새로 확인해야 하며, 삭제 후 같은 테스트 ID로 만들어진 새 계정에는 이전 요청을 재사용할 수 없다. 최대 20명 단위이며 추가 대상은 다시 확인한 뒤 처리한다. 보호 조건 때문에 제외된 계정을 포함해 후보 1,000명까지 조회해도 한 묶음을 확정할 수 없는 경우 409로 중단한다.

안전 조건:

- realm 이름이 환경 UUID에서 파생된 프로젝트 전용 이름인지, realm의 환경 소유 표식이 맞는지 재확인한다.
- `platformMock` 속성은 관리자만 수정할 수 있어야 한다. 테스트 표식·서버가 만드는 아이디 형식·이름·테스트 이메일을 함께 확인하며, 아이디 접두사만으로 삭제하지 않는다.
- 일반 회원·서비스 계정·외부 사용자 저장소 계정·소셜 연결·그룹 소속 계정은 제외한다. 기본 realm 역할 외의 역할, account 외 직접 client 역할, 합성 역할로 부여된 realm 관리 권한도 제외한다. 관리 용도로 바뀐 Mock 계정은 수동 검토 대상이다.
- 삭제 직전 각 계정을 다시 확인한다. 비활성화 → 온라인·오프라인 세션 종료 → 계정 삭제 순서이며, 중간 실패 시 다시 활성화하지 않는다. 이미 발급된 접근 토큰의 자체 검증까지 즉시 차단하는 기능은 아니다.

한 계정 실패가 다른 계정의 성공을 취소하지 않는다. HTTP 200의 결과에도 `failed > 0`이 있을 수 있다. 오류 원문·토큰은 반환하지 않는다. 통신 단절 등으로 응답을 받지 못했다면 감사 이력과 새 미리보기로 반영 상태를 확인한다.

감사 이벤트는 계정별 `mock.user.reset.started`, `mock.user.deleted`, `mock.user.reset.failed`와 묶음 결과 `mock.reset.completed`/`mock.reset.partial`이다. 시작과 결과는 외부 작업을 감싼 독립 DB 트랜잭션으로 기록한다. 작업자·대상 회원 ID·환경 ID·시간을 보존하며 관리자 화면에서 한국어 작업명으로 표시한다. 프로세스 중단·DB 장애로 시작 기록만 남으면 결과 미확정 상태로 조사한다. Keycloak과 DB 간 원자적 완료나 외부 Keycloak 관리자의 동시 변경까지 보장하지 않으므로 초기화 중에는 직접 관리 변경을 병행하지 않는다. 입력·권한·미리보기 불일치 등 작업 진입 전 거부는 삭제 이벤트를 만들지 않는다.

화면 경로: 프로젝트 → DEV 환경 → 개발 로그인 테스트 → **초기화 대상 확인**. 확인창에 프로젝트·환경·계정 목록·이번 삭제 인원을 표시하고 명시적인 체크 후 삭제한다. 취소·오류 후 재확인, 처리 중 중복 실행 방지, 완료 후 회원 목록 갱신과 계정별 결과 표시를 제공한다.

검증 명령:

```sh
./scripts/dev.ps1 --profile test run --rm project-check python /checks/check-mock-reset.py
```

새 검증 프로젝트만 생성해 삭제·보호·온라인/오프라인 세션·동시 요청·감사 이력·20명 묶음을 검사한다. 기본 종료 시 발급 키를 폐기하고 검증 프로젝트를 중지하며, 보호 대상 fixture는 보존한다. `--keep-active`는 화면 검수용으로 성공한 프로젝트를 유지하므로 검수 후 중지한다. 단위 검사는 로그아웃 실패 시 삭제 금지·부분 실패 결과와 독립 감사 기록을 추가로 확인한다.

## 초기 설정과 관리자 인증

기존 설치는 [README](../README.md)의 환경 업그레이드 후 다음 명령을 실행한다.

```sh
./scripts/dev.ps1 --profile setup run --rm --build identity-setup
```

`PLATFORM_MODE=dev`이면 `platform-admin-dev`, `prod`이면 `platform-admin-prod` realm을 사용한다. 초기 플랫폼 관리자 이름은 `admin`, 비밀번호는 `.env.dev` / `.env.prod`의 `PLATFORM_ADMIN_PASSWORD`다. Keycloak의 master 관리자와는 별도 계정이다. 재실행은 기존 사용자 비밀번호를 바꾸지 않는다. 관리자에게 실제 사용할 프로필·MFA·복구 방법을 설정하는 작업은 출시 전 필요하다.

초기화 코드는 `platform-tools` 이미지에 포함된다. 이미지를 게시한 뒤 운영에서는 `docker compose --profile setup run --rm identity-setup`으로 실행할 수 있으며 소스 파일은 필요 없다. 현재는 로컬 빌드만 검증했고 운영 배포·이미지 게시를 완료한 상태는 아니다.

초기화 도구만 master 자격증명을 받는다. 프로젝트 서비스는 `platform-provisioner-환경`의 client credentials를 사용한다. 이 계정은 Keycloak의 `create-realm` 권한으로 realm을 만들고 자신이 만든 realm을 관리한다. 관리자 realm의 회원 목록에는 접근할 수 없다. realm의 최소 정보 조회가 허용되는 경우와 관리 권한은 구분한다. [Keycloak 관리자 권한](https://www.keycloak.org/docs/latest/server_admin/index.html#_per_realm_admin_permissions)

개발 CLI용 토큰은 아래 엔드포인트에 form 요청으로 받는다. 비밀번호·토큰을 터미널 공유 기록이나 Git에 남기지 않는다.

```text
POST /auth/realms/platform-admin-dev/protocol/openid-connect/token
Content-Type: application/x-www-form-urlencoded

grant_type=password
client_id=platform-admin-cli
username=admin
password=<로컬 PLATFORM_ADMIN_PASSWORD>
```

이 CLI는 개발 검사 도구다. 운영 초기화에서는 password grant를 비활성화한다. 관리자 브라우저는 `platform-admin-web` 공개 클라이언트의 Authorization Code + PKCE(S256)를 사용한다. 정확한 `PLATFORM_WEB_URL/`만 로그인·로그아웃 콜백으로 허용한다. 토큰은 메모리에 보관하고 API 요청 전 갱신한다. [Keycloak 공식 JavaScript 어댑터](https://www.keycloak.org/securing-apps/javascript-adapter)를 사용한다. 운영 realm 설정과 UI 코드는 준비했지만 실제 운영 TLS 배포는 아직 검증하지 않았다.

관리 API는 `Authorization: Bearer <access_token>`을 요구한다. 발급자, 서명, 만료, audience `platform-admin-api`와 `platform-admin` 역할을 검사한다. 최종 이용자 토큰과 서버 API 키는 관리자 권한이 아니다. [Spring Security JWT 검증](https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html)

## API 목록

기본 주소는 `http://localhost:30140`이며 JSON을 주고받는다.

| 메서드·경로 | 용도 |
|---|---|
| `POST /api/v1/admin/projects` | `{code, name}` 프로젝트 생성 |
| `GET /api/v1/admin/projects?limit=50&offset=0` | 최대 100건씩 프로젝트 조회 |
| `POST /api/v1/admin/projects/{id}/environments` | 환경 생성 및 realm 구성 시도 |
| `GET /api/v1/admin/projects/{id}/environments` | 환경·issuer·구성 상태 조회 |
| `POST /api/v1/admin/environments/{id}/provision` | 같은 realm 이름으로 실패·중단 작업 재시도 |
| `POST /api/v1/admin/environments/{id}/credentials` | 서버 API 키 생성, 원문 1회 응답 |
| `GET /api/v1/admin/environments/{id}/authentication-policy` | 이메일 로그인·인증·비밀번호 재설정·비밀번호 길이 정책 조회 |
| `PUT /api/v1/admin/environments/{id}/authentication-policy` | 환경별 가입·복구 정책 저장 |
| `GET /api/v1/admin/environments/{id}/email-inbox` | dev 배포·DEV 환경의 최근 1시간 인증 이메일 최대 100건 |
| `GET /api/v1/admin/environments/{id}/social-providers` | 소셜 3종의 설정 메타데이터·콜백 조회 |
| `PUT /api/v1/admin/environments/{id}/social-providers/{provider}` | `kakao`·`naver`·`google` 설정 저장 |
| `GET /api/v1/admin/environments/{id}/credential-scopes` | 해당 환경에서 발급 가능한 권한의 `code`·`label`·`description` 목록 |
| `DELETE /api/v1/admin/credentials/{id}` | API 키 즉시 폐기 |
| `GET /api/v1/admin/audit-events?limit=50` | 최근 관리 이벤트 최대 100건 조회 |
| `GET /api/v1/integration/context` | `X-Platform-Key`의 소속 프로젝트·환경·issuer 확인 |
| `POST /api/v1/dev/login` | 개발 환경 API 키로 소셜 Mock 로그인 |
| `POST /api/v1/admin/environments/{id}/mock-login` | 관리자용 DEV 로그인 검사, 토큰을 제외한 결과 반환 |

코드는 영문 소문자로 시작하는 2~40자이며 영문 소문자·숫자·하이픈을 허용한다. 프로젝트 코드는 전체에서, 환경 코드는 해당 프로젝트 안에서 유일하다. 표시 이름은 120자까지다.

환경 생성 요청 예:

```json
{
  "code": "dev",
  "kind": "DEV",
  "registrationAllowed": true,
  "redirectUris": ["http://localhost:3000/callback"]
}
```

`kind`는 `DEV` 또는 `PROD`다. 환경마다 UUID에 기반한 별도 realm을 생성해 프로젝트 간 및 개발·운영 간 계정·세션을 나눈다. `registrationAllowed`는 필수 boolean이며 현재 Keycloak의 일반 자체 가입 허용 여부다. 소셜별 정책·초대 가입은 아직 제공하지 않는다.

콜백은 1~10개의 정확한 HTTPS URL을 등록한다. DEV에 한해서 HTTP localhost·127.0.0.1·::1도 허용한다. 와일드카드·URL 사용자정보·fragment는 거부한다. realm의 공개 클라이언트 ID는 `app`, 로그인 흐름은 Authorization Code + PKCE(S256)다. 일반 앱의 password grant는 꺼져 있다. 브라우저 CORS·로그아웃 URI 설정 API는 후속 작업이며 현재 임의의 출처를 열지 않는다.

환경 응답의 `state`는 `PENDING`, `READY`, `FAILED`다. DB에 먼저 환경을 기록하고 Keycloak을 구성하므로 HTTP 201은 환경 레코드 생성을 뜻한다. `READY`까지 확인해야 연동할 수 있다. 실패 시 같은 환경의 provision API로 재시도한다. 이름을 재사용해 realm을 중복 생성하지 않으며, 환경 ID 소유 표식이 일치하지 않는 realm은 가져오지 않는다. 재시도는 실패 복구용이며 관리자 콘솔에서 수동으로 바꾼 설정을 동기화하는 기능은 아니다.

## 서버 API 키

키는 환경 하나에 속하며 연동 정보 조회, DEV Mock 로그인과 파일 API에 사용한다. 관리자 화면에서 권한 선택·발급·목록 조회·폐기를 지원한다. 알림별 세부 권한은 후속 작업이다. 브라우저나 공개 JS에 서버 키를 넣지 않는다.

발급 응답은 `{id, apiKey, expiresAt, scopes}`다. 256비트 무작위 비밀값을 사용하고 DB에는 SHA-256 해시만 보관한다. 새 키는 기본 만료 없음이며 `expiresAt: null`을 반환한다. 목록의 `expires_at: null`도 만료 없음을 뜻한다. 기존 90일 키의 만료일은 유지하며 폐기·만료된 키를 되살리지 않는다. 발급 화면에서 ‘만료일 지정’을 선택할 수 있다. API는 본문 생략·`{}`·`{"expiresAt": null}`이면 만료 없음이며, `{"expiresAt": "2027-01-01T00:00:00Z"}`처럼 미래 ISO 8601 시각을 주면 그 시점부터 거부한다. 과거·잘못된 시각은 HTTP 400이다. 화면은 현재 기기 시간대 입력을 UTC로 변환해 전송한다. 교체할 때 새 키를 발급해 서비스를 전환한 뒤 이전 키를 폐기한다. 폐기된 키는 이후 요청부터 거부하며 이미 진행 중인 요청까지 취소하지는 않는다. 발급된 이용자 JWT는 별도 만료 시점까지 유효할 수 있다.

### 키별 사용 권한

| 권한 | 허용하는 요청 | 발급 범위 |
|---|---|---|
| `integration:read` | `GET /api/v1/integration/context` | 모든 환경, 새 키의 기본 권한 |
| `auth:mock` | `POST /api/v1/dev/login` | 플랫폼 개발 모드의 DEV 환경에서만 명시적으로 선택 |
| `files:read` | 파일 목록·정보·비공개 다운로드·파일 API 명세 | 모든 환경, 명시적으로 선택 |
| `files:write` | 업로드 생성·상태·조각 전송·완료·취소, 공개 범위 변경 | 모든 환경, 명시적으로 선택 |
| `files:delete` | 완료 파일 삭제 | 모든 환경, 명시적으로 선택 |
| `files:share` | 비공개 파일 비밀번호 공유 생성·목록·철회 | 모든 환경, 명시적으로 선택 |

파일 API는 [파일 서비스 지침](FILES.md)을 따른다. 기존 키의 권한 배열을 변경하는 마이그레이션은 없다. 업로드 세션은 발급 키에 귀속되고 완료 파일의 서버 관리 권한은 같은 프로젝트·환경 범위다. 파일 권한과 `integration:read`는 독립적으로 선택한다.

```json
{"expiresAt": null, "scopes": ["integration:read", "auth:mock"]}
```

`scopes` 생략 또는 null은 `integration:read`만 부여한다. 빈 배열·중복·알 수 없는 권한·와일드카드·PROD의 `auth:mock` 요청은 400이다. 한 가지 권한만 선택해 발급할 수도 있다. 유효한 키라도 요청에 필요한 권한이 없으면 403이며, 잘못된 키·만료·폐기·프로젝트 중지·환경 미반영은 먼저 검사해 401로 거부한다. 서버 API 키로 관리자 API를 호출할 수 없다.

발급 응답·키 목록·연동 정보에는 선택한 `scopes`가 포함된다. 목록에는 키 원문·해시를 반환하지 않는다. 권한 변경은 새 키 발급 → 연결 서비스의 키 교체 → 이전 키 폐기 순서로 진행한다. V4 마이그레이션은 기존 DEV 키에 두 권한, PROD 키에 조회 권한을 지정해 이전 동작을 유지하며 기존 만료·폐기는 바꾸지 않는다. 앞으로 추가하는 파일·알림 등의 권한은 기존 키에 자동으로 부여하지 않는다.

## 개발 소셜 Mock

```text
POST /api/v1/dev/login
X-Platform-Key: <auth:mock 권한을 선택한 개발 환경 서버 키>
Content-Type: application/json
```

```json
{"provider":"kakao","subject":"test-user","scenario":"success"}
```

provider는 `kakao`, `naver`, `google`, subject는 영문·숫자·밑줄·하이픈 1~80자다. 실제 제공자로 네트워크 요청을 보내지 않는다. 내부 Keycloak에 Mock 전용 사용자와 비밀 클라이언트를 만들고 Keycloak 서명 토큰을 받는다. 같은 환경·제공자·subject는 같은 테스트 사용자로 로그인하며 다른 환경은 별도 사용자다. Mock 소유 표식이 없는 계정의 비밀번호를 덮어쓰지 않는다.

성공 응답은 `accessToken`, `expiresIn`, `tokenType`, `mode: mock`, `scenario: success`, `provider`, `projectId`, `environmentId`, `issuer`, `userId`다. refresh token·내부 클라이언트 비밀값·임시 비밀번호는 반환하지 않는다. 테스트 로그인 시마다 내부 임시 비밀번호를 교체하며 동시 로그인을 프로젝트 단위로 직렬 처리한다. 대규모 동시 Mock 부하는 아직 검증하지 않았다.

`scenario` 생략·null은 기존과 같은 성공이다. 알 수 없는 시나리오는 400으로 거부한다.

| scenario | 연동 API HTTP 상태 | 동작 |
|---|---|---|
| `success` | 200 | 내부 Keycloak 테스트 사용자·세션 생성 및 토큰 발급 |
| `cancelled` | 403 | 로그인 취소 재현 |
| `access_denied` | 403 | 동의 거부 재현 |
| `provider_unavailable` | 503 | 소셜 제공자 장애 재현 |

실패 응답은 `{mode: "mock", scenario, error, provider, projectId, environmentId}`이며 `error`는 시나리오 코드와 같다. 실패 재현은 Keycloak을 호출하거나 사용자를 만들고 변경하지 않는다. 시나리오보다 키 권한·만료·폐기·프로젝트 상태를 먼저 검사하므로 실패 선택으로 인증 검사를 우회할 수 없다. 실행 결과는 `mock.login` 또는 `mock.login.시나리오` 감사 이벤트로 남긴다.

관리자 화면은 동일한 본문을 `POST /api/v1/admin/environments/{id}/mock-login`으로 보낸다. 플랫폼 관리자 JWT가 필요하며 서버 API 키·최종 이용자 JWT로 실행할 수 없다. 정상적으로 검사를 수행했을 때 실제 HTTP 응답은 200이고 본문은 `{httpStatus, result}`다. `httpStatus`는 연동 API가 반환할 상태이며 `result`에는 허용된 결과 메타데이터만 포함한다. 이용자 토큰은 관리자 응답에도 포함하지 않는다. 실제 권한·입력·Keycloak 장애는 일반 4xx/5xx 응답으로 구분한다. 중지된 프로젝트·미반영 환경은 409, PROD 환경은 403이다.

서버가 `PLATFORM_MODE=dev`일 때만 관리자·연동용 Mock 컨트롤러가 등록되고, PROD 환경에서는 실행할 수 없다. 운영 관리자 API는 개발 realm 토큰도 받지 않는다. 잘못된 mode 값은 시작 시 거부한다. 개발 화면·시나리오는 구현했으며 테스트 사용자 일괄 초기화·모의 발송 수신함은 미구현이다. 이 검사는 실제 소셜 리다이렉트·콜백·운영 로그인 검증을 대신하지 않는다.

## 데이터와 검증

프로젝트 서비스의 Flyway가 자기 DB에 프로젝트·환경·키 해시·감사 이벤트 테이블을 생성한다. Keycloak 데이터는 공식 관리 API로만 다룬다. 관리 API에 감사 기록 수정·삭제 기능은 없다.

```sh
./scripts/dev.ps1 --profile test run --rm project-check
./scripts/dev.ps1 --profile test run --rm project-check python /checks/check-mock.py
```

실제 토큰·DB·Keycloak으로 검증한다. 검사 프로젝트 이름은 `check-...`이며 확인용 데이터를 개발 DB에 남긴다. 토큰·키 원문은 출력하지 않는다. 상태 코드 400은 잘못된 입력, 401은 인증 실패, 403은 권한·환경 제한, 409는 중복·준비 미완료를 의미한다.

운영 모드 부정 검사를 재현하려면 다음과 같이 로컬 검사 컨테이너를 사용한다. 개발 공개키를 의도적으로 제공해 서명 검증을 통과할 수 있는 조건에서도 개발 issuer를 거부하는지 확인한다. 실제 운영 배포 명령이 아니다.

```sh
docker compose -f compose.yml run --rm --no-deps --pull never -d --name shnea-platform-dev-prod-auth-check -e PLATFORM_MODE=prod -e SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_JWK_SET_URI=http://keycloak:8080/auth/realms/platform-admin-dev/protocol/openid-connect/certs project-service
./scripts/dev.ps1 --profile test run --rm project-check python /checks/check-prod-mode.py
docker stop shnea-platform-dev-prod-auth-check
```

realm 생성 실패·복구 검사:

```sh
docker compose -f compose.yml run --rm --no-deps --pull never -d --name shnea-platform-dev-failure-check -e KEYCLOAK_PROVISIONER_SECRET=deliberately-invalid project-service
./scripts/dev.ps1 --profile test run --rm project-check python /checks/check-provision-recovery.py
docker stop shnea-platform-dev-failure-check
```

두 검사는 로컬 개발 DB를 사용하며 운영 데이터를 다루지 않는다. 테스트가 실패해도 마지막 중지 명령으로 임시 컨테이너를 정리한다.

## 중지·재개와 설정 변경

| 메서드·경로 | 입력·응답 |
|---|---|
| `PUT /api/v1/admin/projects/{id}` | `{ "name": "프로젝트 이름", "status": "ACTIVE 또는 SUSPENDED", "revision": 0 }` |
| `PUT /api/v1/admin/environments/{id}` | `{ "registrationAllowed": false, "redirectUris": ["https://example.org/callback"], "revision": 0 }` |
| `GET /api/v1/admin/environments/{id}/credentials` | 최근 100개 키의 `id`, `created_at`, `expires_at`, `revoked_at`. 원문·해시 없음 |
| `GET /api/v1/config` | 공개 로그인 설정 `url`, `realm`, `clientId`, `mode`. 비밀값 없음 |

프로젝트 목록은 최신 생성 순서다. 프로젝트·환경 응답의 `revision`을 수정 요청에 넣는다. 오래된 revision은 HTTP 409이며 최신 목록을 다시 읽어야 한다. 코드·환경 종류·소속은 변경하지 않는다. 환경 수정 시 콜백 origin을 정확히 추출해 Keycloak CORS 허용 목록에도 반영한다. 와일드카드는 허용하지 않는다.

중지·재개는 원하는 상태를 프로젝트 DB에 먼저 저장하고 각 realm에 반영한다. 중지는 프로젝트 전체 API 키와 Mock 로그인, 환경·키 신규 발급을 차단한다. Keycloak에서는 realm을 비활성화하고 사용자 세션을 종료한다. 중지 중 로그인 설정을 수정해도 realm은 비활성 상태를 유지한다. 재개는 realm 반영이 READY인 환경부터 유효한 기존 키를 다시 허용한다. 폐기·만료된 키는 되살리지 않는다.

환경 상태 READY는 **현재 원하는 설정의 반영 완료**를 뜻하므로 중지된 프로젝트에서도 READY일 수 있다. PENDING·FAILED는 반영 대기·실패이며 API 키를 차단한다. `POST /api/v1/admin/environments/{id}/provision` 또는 화면의 ‘다시 반영’으로 복구한다. 중단·타임아웃 후에도 DB의 원하는 상태가 남는다. 반영은 환경별로 순차 수행하며 자동 재시도 작업자는 아직 없다. 환경 수가 많으면 HTTP 응답 전에 프록시 타임아웃이 날 수 있으므로 상태를 새로 읽고 미반영 환경만 재시도한다.

이미 발급한 JWT를 외부 서비스가 공개키로 자체 검증하면 realm 중지·세션 종료만으로 즉시 무효화할 수 없다. 기본 access token 수명은 300초이며 기존 토큰은 만료까지 유효할 수 있다. Keycloak 반영 실패 중에는 실제 로그인 차단이 아직 적용되지 않을 수 있다. 즉시 차단이 필요한 서비스의 요청은 플랫폼 상태 확인을 포함해야 한다. HTTP 200만 보지 말고 프로젝트와 환경 상태를 함께 확인한다.

변경·반영·Mock·키 발급은 프로젝트 행 잠금으로 순서를 맞추며 같은 프로젝트의 인증 관리 작업은 직렬 처리한다. 환경 단위 병렬 처리가 필요한 규모가 되면 잠금 전략을 다시 설계한다. API 키 검사는 잠금 대기 후 다시 검증한다. 중지 전에 이미 처리 중이던 요청의 소급 취소를 보장하지는 않는다.

```sh
./scripts/dev.ps1 --profile test run --rm project-check python /checks/check-lifecycle.py
```

검사는 새 `lifecycle-...` 프로젝트에서 동시 수정 충돌, 콜백·가입 반영, 세션 종료, 중지 중 키·Mock·환경 생성 차단, 중지 중 설정 변경, 재개·폐기를 확인한다. 위의 실패 복구 검사는 Keycloak 자격증명 오류 중 중지·재개 상태 보존과 키 차단도 검증한다.

## 소셜 설정 API

플랫폼 관리자 JWT가 필요하다. 서버 API 키와 최종 이용자 토큰으로는 사용할 수 없다. 환경이 READY여야 하며, 중지된 프로젝트도 설정은 수정할 수 있지만 realm 비활성화는 그대로 유지한다.

조회 결과는 제공자별 `code`, `label`, `alias`, `configured`, `enabled`, `credentialsConfigured`, `revision`, `callbackUrl`, `activationAllowed`, `sharedCallback`, `sharedCallbackUrl`이다. Client ID·Secret·관리 토큰은 반환하지 않는다. `credentialsConfigured`는 Keycloak의 공통 환경변수 두 값이 존재하는지 나타내며 실제 로그인 성공 판정이 아니다. `callbackUrl`은 현재 적용 주소, `sharedCallbackUrl`은 전환할 공통 주소다. 미설정 revision은 `unconfigured`다.

```json
{
  "enabled": false,
  "revision": "unconfigured"
}
```

- 키를 API로 입력하지 않는다. `.env.dev` / `.env.prod`의 공통 자격증명을 Keycloak만 읽는다. 프로젝트 서비스는 Keycloak 내부 경로 `/realms/master/platform-social/configuration`에서 준비 여부만 확인한다. 해당 경로는 외부 Nginx에서 차단한다.
- `enabled`·`revision`은 필수다. 실제 활성화는 운영 모드·PROD·공통 키 준비 상태에서만 허용한다. 미준비 상태에서도 비활성 설정 저장·기존 활성 설정 끄기는 가능하다.
- 프로젝트 잠금과 revision으로 동시 저장 중 한 건만 성공하고 나머지는 409다. 소유 표시가 없는 Keycloak 설정은 덮어쓰지 않는다.
- 기존 관리 대상 `oidc`·`google` 설정도 읽을 수 있다. 저장 시 같은 별칭을 유지하면서 `platform-kakao`·`platform-google`로 전환하고 공통 모드를 적용한다. 기존 회원 연결을 삭제하거나 비밀키를 API로 조회하지 않는다.
- 감사 이벤트에는 제공자·환경만 기록하며 자격증명은 기록하지 않는다. 콜백 검증·운영 적용은 [공통 소셜 로그인](SOCIAL_LOGIN.md)을 참고한다.

Keycloak의 기본 `first broker login`, `storeToken=false`를 유지하고 소셜 설정 저장 시 `trustEmail=true`를 적용한다. 정상 구글·카카오·네이버 인증 뒤 제공자 이메일과 로컬 이메일이 일치하면 추가 이메일 인증을 생략한다. 기존 `trustEmail=false` 플랫폼 어댑터/미인증 소셜 회원도 다음 소셜 로그인에서 처리하며 전체 회원을 일괄 인증하지 않는다. 제공자 이메일 누락·다른 로컬 이메일·프로필 확인에서 직접 입력/변경한 이메일은 자동 인증하지 않고, 일반 가입·로그인의 필수 인증과 다른 필수 작업도 유지한다. 이메일만으로 기존 계정을 자동 연결하지 않는다. 연결 서비스는 원래 realm의 공개 클라이언트 `app`과 Authorization Code + PKCE(S256)를 사용하며, `kc_idp_hint=platform-kakao`처럼 제공자 별칭을 지정할 수 있다. 서비스 복귀 주소는 환경의 `redirectUris`에 별도 등록한다.

로컬 설정 검증 명령(비활성 제공자와 가짜 전환 설정만 사용, 외부 소셜 로그인 없음):

```sh
./scripts/dev.ps1 --profile test run --rm project-check python /checks/check-social-settings.py
```

검사 후 가짜 소셜 설정과 검사 API 키를 제거/폐기하고 `social-settings-...` 프로젝트를 중지 상태로 남긴다.

## 가입·계정 복구 정책

기존 `registrationAllowed`는 일반 자체 가입 허용 여부이며 환경 생성·설정 API에서 계속 관리한다. 소셜 가입 허용이나 초대 가입을 의미하지 않는다. 추가 정책은 `/api/v1/admin/environments/{id}/authentication-policy`에서 관리한다. 관리자 JWT와 READY 환경이 필요하다. 프로젝트 중지 중에도 정책은 수정할 수 있지만 로그인 realm을 활성화하지 않는다.

```json
{
  "loginWithEmail": true,
  "verifyEmail": false,
  "resetPasswordAllowed": false,
  "passwordMinLength": 12,
  "revision": "unconfigured"
}
```

모든 필드는 필수다. `passwordMinLength`는 12~128의 정수이며 최대 길이는 항상 128자로 설정한다. 새 환경은 `length(12) and maxLength(128)`을 사용한다. 기존 환경은 자동 변경하지 않으며, 최소 길이가 없으면 GET의 `passwordMinLength`는 0이다. UI의 제안 값 12를 저장해야 반영된다. 별도 규칙이 있으면 -1과 `passwordPolicyEditable=false`를 반환하고 저장은 409로 거부한다. 관리 가능한 규칙은 비어 있는 정책, `length(n)`, `length(n) and maxLength(128)`이며 그 외 Keycloak 수동 설정을 약화하거나 삭제하지 않는다.

`loginWithEmail`은 기존 아이디 외에 이메일로도 로그인할 수 있는지다. 아이디 자체가 이메일인 계정의 아이디를 바꾸지는 않는다. `verifyEmail`을 켜면 기존 미인증 계정도 이후 로그인에서 이메일 확인을 요구받을 수 있다. `resetPasswordAllowed`는 Keycloak의 이메일 기반 자격증명 복구 흐름을 사용한다. 소셜 제공자의 비밀번호를 변경하지 않는다. 플랫폼 관리자 realm·MFA 복구 정책은 이 API의 대상이 아니다. [Keycloak 로그인·복구 설정](https://www.keycloak.org/docs/latest/server_admin/)

조회는 위의 설정과 `passwordPolicyEditable`, `emailActionsAvailable`, `emailDelivery`를 반환한다. `emailDelivery`는 dev/DEV의 `MOCK`, 준비된 prod/PROD의 `NCP`, 그 외 `UNAVAILABLE`이다. 알림 서비스가 응답하지 않거나 설정이 없으면 이메일 옵션을 켤 수 없으며 400을 반환한다. 발송 준비는 실제 수신 성공과 다르다. 기존 이메일 옵션을 끄는 변경은 가능하다. 중지 프로젝트에서 이메일 옵션 활성화, 수동 이메일 중복 허용이 설정된 realm에서 이메일 로그인 활성화는 409다. SMTP host/from 존재만으로 준비 상태를 판단하지 않는다.

수신함은 관리자 JWT 전용이며 API 키·일반 회원 토큰을 허용하지 않는다. 각 항목은 `id`, `recipient`, `subject`, `textBody`, `createdAt`이다. prod 배포·PROD 환경은 404, 존재하지 않는 환경은 404다. 중지한 DEV 환경의 기존 메일은 보존 시간 안에서 조회할 수 있다. 응답은 `Cache-Control: no-store`이며 화면은 HTML을 실행하지 않는다. 내부 전달 계약·중복 방지·보존 정책은 [인증 이메일](IDENTITY_EMAIL.md)에 있다.

정책은 Keycloak을 원본으로 사용한다. 프로젝트 DB에는 `authentication.policy.updated` 감사 이벤트만 기록한다. Keycloak realm 속성의 `platform.authPolicyRevision`과 프로젝트 행 잠금으로 플랫폼 내 동시 수정을 검사한다. 조회한 revision으로 저장하며 성공 시 새로운 revision을 반환한다. 오래된 revision은 409다. 다른 관리자가 Keycloak을 직접 편집하는 경로와 동시에 사용하지 않는다. 502나 통신 실패가 나면 이미 반영되었을 수 있으므로 다시 조회한 뒤 저장한다.

기존 일반 가입·콜백 변경, 프로젝트 중지·재개와 realm 재반영은 추가 정책을 보존한다. 기존 비밀번호·사용자별 별도 필수 작업·현재 세션은 변경하지 않는다. 비밀번호 길이 규칙은 새 가입·비밀번호 변경/재설정부터 적용한다. 정책을 저장해도 사용자 이메일 발송이나 실제 로그인 검사를 실행하지 않는다.

```sh
./scripts/dev.ps1 --profile test run --rm project-check python /checks/check-authentication-policy.py
```

검사는 가짜 로컬 계정에 대한 비밀번호 설정으로 길이 제한을 확인한 뒤 계정을 삭제한다. 이메일·외부 소셜 로그인은 실행하지 않으며 검사 API 키는 폐기하고 프로젝트는 중지 상태로 남긴다.

## 프로젝트 파일 서비스 사용

프로젝트 생성의 `filesEnabled`는 기본 false다. 기존 프로젝트는 마이그레이션으로 사용 상태를 유지한다. 관리자 `PUT /api/v1/admin/projects/{id}/files`의 `{enabled, revision}`으로 모든 환경에 적용하며 설정 충돌은 409다. 파일 권한은 사용 중일 때 발급할 수 있고 사용을 끄면 기존 키도 파일 API만 403 FILE_SERVICE_DISABLED로 차단한다. 기존 키·파일·보존 설정은 유지한다. 사용자별 업로드 권한은 호스트 서버가 담당한다. 자세한 계약은 [파일 서비스](FILES.md)를 따른다.
