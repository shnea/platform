# 프로젝트·인증 API

## 현재 제공 범위

프로젝트 등록·목록, 환경 등록·목록, Keycloak realm 생성·재시도, 서버 API 키 발급·폐기, 연동 환경 확인, 감사 이벤트 조회, 개발 소셜 Mock 로그인 API를 제공한다. 관리자 UI, 프로젝트 중지·재개와 이름 변경, 환경 설정 수정, 키 메타데이터 목록도 제공한다. 실제 소셜 제공자·초대/복구 정책·키별 기능 권한·알림 Mock은 후속 작업이다.

## 초기 설정과 관리자 인증

기존 설치는 [README](../README.md)의 환경 업그레이드 후 다음 명령을 실행한다.

```sh
docker compose -f compose.yml -f compose.dev.yml --profile setup run --rm --build identity-setup
```

`PLATFORM_MODE=dev`이면 `platform-admin-dev`, `prod`이면 `platform-admin-prod` realm을 사용한다. 초기 플랫폼 관리자 이름은 `admin`, 비밀번호는 `.env`의 `PLATFORM_ADMIN_PASSWORD`다. Keycloak의 master 관리자와는 별도 계정이다. 재실행은 기존 사용자 비밀번호를 바꾸지 않는다. 관리자에게 실제 사용할 프로필·MFA·복구 방법을 설정하는 작업은 출시 전 필요하다.

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

키는 환경 하나에 속하며 현재는 연동 정보 조회와 DEV Mock 로그인에만 사용한다. 관리자 화면에서 발급·목록 조회·폐기를 지원하며 파일·알림별 세부 권한은 아직 없다. 브라우저나 공개 JS에 서버 키를 넣지 않는다.

발급 응답은 `{id, apiKey, expiresAt, scopes}`다. 256비트 무작위 비밀값을 사용하고 DB에는 SHA-256 해시만 보관한다. 새 키는 기본 만료 없음이며 `expiresAt: null`을 반환한다. 목록의 `expires_at: null`도 만료 없음을 뜻한다. 기존 90일 키의 만료일은 유지하며 폐기·만료된 키를 되살리지 않는다. 발급 화면에서 ‘만료일 지정’을 선택할 수 있다. API는 본문 생략·`{}`·`{"expiresAt": null}`이면 만료 없음이며, `{"expiresAt": "2027-01-01T00:00:00Z"}`처럼 미래 ISO 8601 시각을 주면 그 시점부터 거부한다. 과거·잘못된 시각은 HTTP 400이다. 화면은 현재 기기 시간대 입력을 UTC로 변환해 전송한다. 교체할 때 새 키를 발급해 서비스를 전환한 뒤 이전 키를 폐기한다. 폐기된 키는 이후 요청부터 거부하며 이미 진행 중인 요청까지 취소하지는 않는다. 발급된 이용자 JWT는 별도 만료 시점까지 유효할 수 있다.

### 키별 사용 권한

| 권한 | 허용하는 요청 | 발급 범위 |
|---|---|---|
| `integration:read` | `GET /api/v1/integration/context` | 모든 환경, 새 키의 기본 권한 |
| `auth:mock` | `POST /api/v1/dev/login` | 플랫폼 개발 모드의 DEV 환경에서만 명시적으로 선택 |

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
docker compose -f compose.yml -f compose.dev.yml --profile test run --rm project-check
docker compose -f compose.yml -f compose.dev.yml --profile test run --rm project-check python /checks/check-mock.py
```

실제 토큰·DB·Keycloak으로 검증한다. 검사 프로젝트 이름은 `check-...`이며 확인용 데이터를 개발 DB에 남긴다. 토큰·키 원문은 출력하지 않는다. 상태 코드 400은 잘못된 입력, 401은 인증 실패, 403은 권한·환경 제한, 409는 중복·준비 미완료를 의미한다.

운영 모드 부정 검사를 재현하려면 다음과 같이 로컬 검사 컨테이너를 사용한다. 개발 공개키를 의도적으로 제공해 서명 검증을 통과할 수 있는 조건에서도 개발 issuer를 거부하는지 확인한다. 실제 운영 배포 명령이 아니다.

```sh
docker compose -f compose.yml run --rm --no-deps --pull never -d --name shnea-platform-dev-prod-auth-check -e PLATFORM_MODE=prod -e SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_JWK_SET_URI=http://keycloak:8080/auth/realms/platform-admin-dev/protocol/openid-connect/certs project-service
docker compose -f compose.yml -f compose.dev.yml --profile test run --rm project-check python /checks/check-prod-mode.py
docker stop shnea-platform-dev-prod-auth-check
```

realm 생성 실패·복구 검사:

```sh
docker compose -f compose.yml run --rm --no-deps --pull never -d --name shnea-platform-dev-failure-check -e KEYCLOAK_PROVISIONER_SECRET=deliberately-invalid project-service
docker compose -f compose.yml -f compose.dev.yml --profile test run --rm project-check python /checks/check-provision-recovery.py
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
docker compose -f compose.yml -f compose.dev.yml --profile test run --rm project-check python /checks/check-lifecycle.py
```

검사는 새 `lifecycle-...` 프로젝트에서 동시 수정 충돌, 콜백·가입 반영, 세션 종료, 중지 중 키·Mock·환경 생성 차단, 중지 중 설정 변경, 재개·폐기를 확인한다. 위의 실패 복구 검사는 Keycloak 자격증명 오류 중 중지·재개 상태 보존과 키 차단도 검증한다.

## 소셜 설정 API

플랫폼 관리자 JWT가 필요하다. 서버 API 키와 최종 이용자 토큰으로는 사용할 수 없다. 환경이 READY여야 하며, 중지된 프로젝트도 설정은 수정할 수 있지만 realm 비활성화는 그대로 유지한다.

조회 결과는 제공자별 `code`, `label`, `alias`, `configured`, `enabled`, `clientId`, `secretConfigured`, `revision`, `callbackUrl`, `activationAllowed`다. 비밀키·Keycloak 비밀키 마스크·관리 토큰은 반환하지 않는다. 미설정 revision은 `unconfigured`다. 조회·저장은 외부 소셜 인증 요청을 실행하지 않는다.

```json
{
  "clientId": "<해당 소셜 앱의 클라이언트 ID 또는 REST API 키>",
  "clientSecret": "<해당 앱의 비밀키>",
  "enabled": false,
  "revision": "unconfigured"
}
```

- `clientId`: 공백 제외 필수, 최대 512자. `clientSecret`: 최대 4096자. 처음 저장하거나 clientId가 바뀌면 비밀키가 필요하다. 기존 clientId를 유지하면서 비밀키를 생략/null/빈 문자열로 보내면 기존 비밀키를 유지한다. 새 비밀키를 보내면 교체한다.
- `enabled`와 `revision`은 필수다. 실제 로그인 활성화는 `PLATFORM_MODE=prod`이면서 PROD 환경일 때만 가능하다. 개발 환경에서는 비활성 설정만 보관할 수 있다. 설정 저장을 연결 성공으로 해석하지 않는다.
- 같은 환경·제공자의 조회 revision으로 저장한다. 프로젝트 행 잠금과 Keycloak 설정 안의 revision을 함께 사용한다. 동시 저장 중 한 건만 성공하고 나머지는 409다. 플랫폼 외부의 Keycloak 직접 편집은 이 충돌 검사의 대상이 아니므로 관리 경로를 혼용하지 않는다.
- 소셜 설정·비밀키의 원본은 Keycloak이다. 프로젝트 DB에 복제하지 않는다. 키 유지 시 Keycloak 관리 API가 반환한 비밀키 마스크를 그대로 PUT해 원본을 보존한다. 관리 API는 명시적으로 이 동작을 지원한다. [Keycloak 26.7.4 구현](https://github.com/keycloak/keycloak/blob/26.7.4/services/src/main/java/org/keycloak/services/resources/admin/IdentityProviderResource.java)
- 별칭은 `platform-kakao`, `platform-naver`, `platform-google`로 고정한다. 같은 별칭에 플랫폼 소유 표식이 없는 설정이 있으면 409로 거부하며 덮어쓰지 않는다. 임의 제공자·외부 엔드포인트 URL은 받지 않는다.
- 잘못된 입력·개발 활성화 400, 권한 없음 401/403, 없는 환경 404, 미반영·revision/소유권 충돌 409, Keycloak 요청 실패 502다. 외부 반영 이후 응답 손실이나 DB 감사 기록 실패가 생길 수 있으므로 실패 후에는 조회부터 다시 하고 최신 revision으로 저장한다.
- 감사 이벤트는 `social.updated.kakao/naver/google`와 환경 ID만 기록한다. 요청의 키·비밀키는 기록하지 않는다. Keycloak DB·백업 접근은 비밀정보 접근으로 관리해야 한다. 저장 암호화가 자동 제공된다고 가정하지 않는다.

Keycloak의 기본 `first broker login` 흐름과 `trustEmail=false`, `storeToken=false`를 사용한다. 동일 이메일만으로 기존 계정을 자동 연결하지 않는다. Keycloak의 관리자 직접 변경으로 기본 흐름을 완화하면 플랫폼 밖의 변경이므로 운영 설정 검수 대상이다.

소셜 앱에 등록할 콜백은 `{issuer}/broker/{alias}/endpoint`다. 연결 서비스는 기존 realm의 공개 클라이언트 `app`과 Authorization Code + PKCE(S256)를 사용한다. `kc_idp_hint=platform-kakao`처럼 제공자 별칭을 지정하면 해당 제공자로 진입할 수 있다. 이는 실제 활성화 이후의 사용법이며 이번 작업에서는 외부 로그인으로 검증하지 않았다. 서비스 콜백은 환경의 `redirectUris`에 별도로 등록해야 한다. [Keycloak identity brokering](https://www.keycloak.org/docs/latest/server_admin/)

로컬 설정 검증 명령(가짜 키·비활성 제공자만 사용, 외부 소셜 로그인 없음):

```sh
docker compose -f compose.yml -f compose.dev.yml --profile test run --rm project-check python /checks/check-social-settings.py
```

검사 후 가짜 소셜 설정과 검사 API 키를 제거/폐기하고 `social-settings-...` 프로젝트를 중지 상태로 남긴다.
