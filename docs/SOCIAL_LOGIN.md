# 공통 소셜 로그인 설정

네이버·구글·카카오별 공통 앱 키 한 세트를 `.env.dev` / `.env.prod`에 등록한다. 프로젝트·환경에서는 제공자 사용 여부만 설정한다. 회원·가입 정책·로그인 세션·토큰은 기존 프로젝트별 Keycloak realm에 남으며 통합 계정이나 플랫폼 SSO를 만들지 않는다.

## 환경변수

```dotenv
SOCIAL_NAVER_CLIENT_ID=
SOCIAL_NAVER_CLIENT_SECRET=
SOCIAL_GOOGLE_CLIENT_ID=
SOCIAL_GOOGLE_CLIENT_SECRET=
SOCIAL_KAKAO_CLIENT_ID=
SOCIAL_KAKAO_CLIENT_SECRET=
```

카카오 Client ID에는 REST API 키를 넣고 카카오 로그인·OpenID Connect·Client Secret을 활성화한다. 구글은 웹 애플리케이션 OAuth 클라이언트를 사용한다. 네이버는 네이버 로그인 애플리케이션의 키를 사용한다. 실제 값은 `.env.dev`·`.env.prod`에서 Dotenvx로 암호화한다. 평문·복호화 키를 Git·화면·문서·채팅에 넣지 않는다.

설정 추가·수정은 [암호화 환경 관리](NAS_DEPLOYMENT.md#설정-변경)를 따른다. 기존 비밀번호·내부 키를 재생성하지 않는다.

키 수정 후에는 `restart`만 하지 말고 컨테이너를 재생성한다. 운영의 이미지 갱신은 별도 배포 절차를 따른다.

```sh
./scripts/dev.ps1 up -d --no-deps --no-build --force-recreate keycloak
```

위 명령은 개발용이다. 운영에서는 같은 태그를 deploy 스크립트에 전달한다. 공통 키는 Keycloak 컨테이너만 읽으며 프로젝트 서비스는 내부 API에서 등록 여부만 확인한다. 관리자 화면에서 설정을 새로고침하면 준비 여부가 갱신된다. 등록됨은 두 값의 존재 여부이며 제공자 검수·정확성·실제 로그인 성공까지 검증한 것은 아니다.

## 외부 콘솔에 등록할 주소

운영 `KEYCLOAK_PUBLIC_URL=https://platform.shnea.kr/auth` 기준이다.

| 제공자 | 콜백 URL |
|---|---|
| 네이버 | `https://platform.shnea.kr/auth/social/naver/callback` |
| 구글 | `https://platform.shnea.kr/auth/social/google/callback` |
| 카카오 | `https://platform.shnea.kr/auth/social/kakao/callback` |

같은 앱에 공통 주소를 등록하면 프로젝트·환경이 늘어도 콜백을 추가하지 않는다. 환경의 서비스 콜백(플랫폼 로그인 후 각 서비스로 돌아갈 주소)은 계속 프로젝트별로 설정한다. 개발·운영 도메인은 별도이며 DEV에서는 실제 키가 있어도 Mock을 사용한다. 실제 로그인은 `PLATFORM_MODE=prod`와 PROD 환경에서만 켠다.

외부 앱의 공동 사용은 제공자 서비스 범위·도메인·동의·검수 정책을 따라야 한다. [네이버 콜백 제한](https://github.com/naver/naver-openapi-guide/blob/master/ko/appregister.md), [카카오 서비스별 앱 기준](https://developers.kakao.com/docs/ko/app-setting/app). 현재 구현은 제공자별 공통 키 한 세트를 지원한다. 프로젝트별로 별도 소셜 앱을 써야 하는 경우에는 별도 연결 설정 기능을 추가해야 하며, 공통 콜백을 이유로 정책을 우회하지 않는다.

## 기존 설정 전환

기존 개별 콜백 설정은 자동 변경하지 않는다. 외부 콘솔에 공통 주소를 먼저 추가하고 관리자 화면에서 소셜 설정을 저장하면 같은 별칭을 유지하며 공통 모드로 전환한다. 새 설정에는 Client ID·Secret을 저장하지 않는다. 기존 외부 앱과 콜백을 삭제하지 않는다. 다른 앱의 Client ID로 바꾸면 제공자의 사용자 식별자·동의 범위가 달라질 수 있으므로 실제 회원이 있는 환경에서는 별도 이전 검토가 필요하다.

## 요청 처리와 보안 경계

1. 원래 realm이 표준 소셜 로그인 요청을 만든다. Keycloak의 state·PKCE·nonce를 보존하고 외부 redirect_uri만 공통 주소로 바꾼다.
2. Keycloak 일회용 저장소에 realm·제공자·설정 revision·자격증명 지문·만료 시각을 기록한다. 요청별 HttpOnly·SameSite=Lax 쿠키로 브라우저를 확인한다. HTTPS에서는 Secure를 사용한다. 유효기간은 5분이며 보관소의 TTL 외에 시각을 직접 검증한다.
3. 공통 콜백은 브라우저·제공자·활성 realm·설정 변경 여부를 확인한 뒤 원자적으로 요청을 소비하고 원래 realm의 기본 broker 콜백으로 전달한다. 사용자 입력의 프로젝트·복귀 URL은 신뢰하지 않는다. 오류 설명과 임의 파라미터는 전달하지 않는다.
4. 기본 broker가 원래 state·인증 세션을 검증한다. 코드 교환 직전 공통 콜백에서 만든 일회용 전달 기록과 코드 지문을 확인하며 동일한 고정 redirect_uri를 사용한다. 공통 콜백을 건너뛴 직접 코드 교환·다른 코드·재사용은 거부한다. 네이버 토큰 요청에도 원래 state를 전달한다.

사용자 프로필·토큰 검증은 Keycloak 기존 구현을 사용한다. 별도 DB·Redis·외부 포트를 추가하지 않는다. 기존 master realm은 REST 라우팅 리소스만 제공하며 공통 사용자 저장소로 쓰지 않는다. 프로세스 재시작이나 기록 유실로 진행 중 로그인이 실패하면 원래 서비스에서 다시 시작한다. 오류 로그에 요청 URL·코드·비밀값을 추가하지 않는다.

## 검증

관련 이미지를 빌드한 후, 실제 DB·계정·네트워크와 분리된 검사 환경을 실행한다. 테스트 전용 가짜 키이며 외부 네트워크와 호스트 포트가 없다.

```sh
./scripts/dev.ps1 build keycloak project-service admin-web nginx
docker compose --env-file .env.example -p platform-callback-check -f compose.test.yml --profile callbacks up --abort-on-container-exit --exit-code-from callback-check
docker compose --env-file .env.example -p platform-callback-check -f compose.test.yml --profile callbacks down
./scripts/dev.ps1 --profile test run --rm project-check python /checks/check-social-settings.py
```

단위 검사는 만료·브라우저/제공자 불일치·코드 교체·중지 realm·설정 변경·재사용 및 코드 교환 주소를 확인한다. 격리 통합 검사는 실제 Keycloak에서 공통 환경변수·세 제공자의 로그인 시작·두 realm의 콜백 분리·취소·직접 콜백 우회 차단을 확인한다. 실제 네이버·구글·카카오 로그인·외부 토큰 교환·운영 TLS는 별도 실제 연동 검수로 확인하며 최신 결과는 STATUS.md에 기록한다. Keycloak SPI 버전을 바꾸면 이 검사를 다시 수행한다.

2026-09-26 실제 검수에서 세 제공자의 계정 연결을 확인했다. 카카오 `KOE006`은 공통 콜백을 로그인 리다이렉트에 추가해 해결했다. 이 주소를 로그아웃 리다이렉트에만 등록하면 로그인에 사용할 수 없다.

## NPM 등 외부 HTTPS 프록시

NPM이 HTTPS를 종료하고 플랫폼의 HTTP 30140으로 전달한다면 공개 주소 두 값을 `https://platform.shnea.kr` 및 `https://platform.shnea.kr/auth`로 설정한다. 원격 NPM이 접근할 수 있도록 `BIND_ADDRESS`를 실제 LAN 주소 또는 `0.0.0.0`으로 지정한다. 개발 기본값 `127.0.0.1`로는 다른 장비에서 접근할 수 없다. DB는 계속 루프백 30141만 사용한다.

Nginx는 `KEYCLOAK_PUBLIC_URL`의 scheme을 Keycloak에 전달한다. 사용자 요청의 전달 헤더를 그대로 신뢰하지 않는다. URL 변경 후 Nginx·Keycloak·프로젝트 서비스를 재생성하고 `identity-setup`을 다시 실행해 관리자 복귀 주소를 갱신한다. 이후 HTTPS 주소에서 다시 로그인한다. `PLATFORM_MODE`의 dev/prod는 관리자 realm·provisioner까지 구분하므로 개발 DB를 영구 운영 전환하는 작업과 일시 검수를 구분해야 한다.
