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
| `DELETE /api/v1/admin/credentials/{id}` | API 키 즉시 폐기 |
| `GET /api/v1/admin/audit-events?limit=50` | 최근 관리 이벤트 최대 100건 조회 |
| `GET /api/v1/integration/context` | `X-Platform-Key`의 소속 프로젝트·환경·issuer 확인 |
| `POST /api/v1/dev/login` | 개발 환경 API 키로 소셜 Mock 로그인 |

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

키는 환경 하나에 속하며 현재는 연동 정보 조회와 DEV Mock 로그인에만 사용한다. 파일·알림별 세부 권한과 키 관리 UI는 아직 없다. 브라우저나 공개 JS에 서버 키를 넣지 않는다.

발급 응답은 `{id, apiKey, expiresAt}`다. 256비트 무작위 비밀값을 사용하고 DB에는 SHA-256 해시만 보관한다. 유효기간은 90일이다. 교체할 때 새 키를 발급해 서비스를 전환한 뒤 이전 키를 폐기한다. 폐기된 키는 이후 요청부터 거부하며 이미 진행 중인 요청까지 취소하지는 않는다. 발급된 이용자 JWT는 별도 만료 시점까지 유효할 수 있다.

## 개발 소셜 Mock

```text
POST /api/v1/dev/login
X-Platform-Key: <개발 환경 서버 키>
Content-Type: application/json
```

```json
{"provider":"kakao","subject":"test-user"}
```

provider는 `kakao`, `naver`, `google`, subject는 영문·숫자·밑줄·하이픈 1~80자다. 실제 제공자로 네트워크 요청을 보내지 않는다. 내부 Keycloak에 Mock 전용 사용자와 비밀 클라이언트를 만들고 Keycloak 서명 토큰을 받는다. 같은 환경·제공자·subject는 같은 테스트 사용자로 로그인하며 다른 환경은 별도 사용자다. Mock 소유 표식이 없는 계정의 비밀번호를 덮어쓰지 않는다.

응답은 `accessToken`, `expiresIn`, `tokenType`, `mode: mock`, `provider`, `projectId`, `environmentId`, `issuer`다. refresh token·내부 클라이언트 비밀값·임시 비밀번호는 반환하지 않는다. 테스트 로그인 시마다 내부 임시 비밀번호를 교체하며 동시 로그인을 환경 단위로 직렬 처리한다. 대규모 동시 Mock 부하는 아직 검증하지 않았다.

서버가 `PLATFORM_MODE=dev`일 때만 Mock 컨트롤러가 등록되고, PROD 환경의 API 키로는 호출할 수 없다. 운영 관리자 API는 개발 realm 토큰도 받지 않는다. 잘못된 mode 값은 시작 시 거부한다. 개발용 프로필 선택 화면·실패 시나리오 UI·모의 발송 수신함은 미구현이다. 실제 소셜 연동이나 운영 로그인 검증을 대신하지 않는다.

## 데이터와 검증

프로젝트 서비스의 Flyway가 자기 DB에 프로젝트·환경·키 해시·감사 이벤트 테이블을 생성한다. Keycloak 데이터는 공식 관리 API로만 다룬다. 관리 API에 감사 기록 수정·삭제 기능은 없다.

```sh
docker compose -f compose.yml -f compose.dev.yml --profile test run --rm project-check
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
