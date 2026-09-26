# MSA 실행 기반

파일 관리자는 별도 파일 메뉴와 파일 서비스의 관리자 JWT API를 사용한다. 서버 키와 관리자 업로드 소유권은 종류·식별자로 분리하며 프로젝트·환경의 현재 상태는 프로젝트 서비스 내부 API에서 확인한다. 다운로드는 짧은 일회용 링크, 비공개 미리보기/부분 전송은 최대 5분의 파일별 보기 링크, HLS 조각은 최대 2시간의 재생 전용 권한으로 구분한다. file-service 내부의 영속 썸네일/HLS 변환 큐와 보존 정리기가 같은 파일 DB·볼륨을 사용하며 FFmpeg는 제한된 작업 프로세스로만 실행한다. 동일 내용 조회는 서버가 검증한 READY 원본의 SHA-256·크기와 프로젝트·환경을 비교하며 부분 인덱스를 사용한다. 물리 파일이나 권한·보존 정책을 합치지 않는다. 구체적인 경계와 제약은 [파일 서비스](FILES.md)를 따른다.

이 문서는 첫 구현 단위의 실제 구성을 설명한다. 전체 기능 범위는 [요구사항](REQUIREMENTS.md)에 있다.

## 서비스와 데이터 소유권

| 서비스 | 이미지 접미사 | DB·접속 계정 | 현재 구현 |
|---|---|---|---|
| Nginx | nginx | 없음 | 30140 진입·상태 라우팅·Keycloak 프록시 |
| 관리자 웹 | admin-web | 없음 | React·TypeScript UI, Keycloak 브라우저 로그인, 테마·프로젝트·키·감사 관리 |
| 프로젝트 | project-service | platform_project | 프로젝트·환경·API 키·감사·개발 Mock, 내부/외부 Job 큐, 공통 로그 인증·마스킹·한도·조회 |
| 공통 로그 저장소 | 외부 이미지 `grafana/loki:3.7.0` | 환경 UUID별 tenant·전용 파일 볼륨 | 로그·색인·WAL, 7일 보존과 물리 정리 |
| 파일 | file-service | platform_file | 업로드·재개, 원본/미리보기/썸네일·부분 전송·HLS 변환/재생, 보기 권한, 보존 정책·자동 정리·감사와 영속 볼륨 |
| 알림 | notification-service | platform_notification | 인증 메일 모의 수신함·NCP 전달·상태/보존 관리, 내부 오류/요청 추적 |
| Keycloak | keycloak | platform_identity | PostgreSQL 기반 인증 엔진·프로젝트 realm·소셜 브로커 |
| PostgreSQL | postgres | platform_admin(초기화·운영 전용) | 네 DB와 개별 소유자 생성 |
| 인증 초기화 도구 | tools | DB 직접 접근 없음 | setup 프로필로만 실행, 관리자 realm·서비스 계정 설정 |

자체 이미지는 `register.shnea.kr/platform-접미사:IMAGE_TAG`로 빌드한다. Loki는 고정 버전 외부 이미지를 사용한다. 현재 로컬 태그는 `0.1.0-dev`다. 레지스트리에 게시한 상태는 아니다.

하나의 PostgreSQL 서버에 별도 DB와 비밀번호를 둔다. 서비스 계정에는 다른 DB의 CONNECT 권한이 없다. DB 관리자와 Docker 호스트 관리자는 모든 DB를 관리할 수 있으므로 물리적인 장애·관리자 격리는 아니다. 서비스에는 자기 비밀번호만 전달한다. Keycloak DB에 접근하는 신규 업무 코드는 만들지 않는다.

`app` 네트워크는 프록시·API·Keycloak이 사용하고, `database`는 외부 경로가 없는 내부 네트워크다. 운영 구성에서 DB는 후자에만 연결한다. 개발용 Compose는 DB에 `db-access` 브리지를 추가해 호스트 포트 30141을 127.0.0.1에 연다. API와 Keycloak에는 호스트 포트가 없다.

각 API는 개별 Gradle 하위 프로젝트, 프로세스, 이미지다. Kotlin DSL로 빌드하고 Gradle은 Docker 이미지에 고정한다. `libraries:http`는 공통 오류 직렬화·요청 ID·내부 누적 지표를 제공하며 별도 프로세스나 DB를 만들지 않는다. 서비스 간 DB 조인은 없다. 프로젝트 관리 API는 별도 관리자 realm의 JWT·audience·역할로 보호하며, 연동·Mock API는 환경별 서버 키를 검증한다. 파일 서비스는 전용 내부 인증키로 프로젝트 서비스에 파일 권한을 확인하고 자기 DB와 `file-data` 영속 볼륨만 사용한다. 세부 계약과 단계별 미완료 범위는 [파일 서비스](FILES.md)를 따른다.

## 버전 선택

외부 Job은 프로젝트 DB의 별도 영속 큐다. 각 프로젝트 워커가 권한이 있는 환경에서 작업을 점유하고, 60초 점유 연장·완료/실패를 보고한다. 플랫폼은 만료 복구와 제한된 재시도를 담당하며 외부 코드를 실행하지 않는다. 내부 Job·Outbox와 분리하고 관리자 화면에서 탭으로 구분한다. 상세 계약은 [Job 연결 지침](integration/JOBS.md)을 따른다.

공통 로그는 프로젝트 서비스가 서버 키를 검증해 환경 UUID를 Loki tenant로 지정한다. 클라이언트가 tenant를 선택할 수 없다. 전용 내부 `logs` 네트워크에는 project-service와 Loki만 연결하며 Loki 호스트 포트는 없다. 환경별 분당·하루 한도와 전송 전 마스킹을 적용한다. 저장소의 7일 보존·삭제 지연, 송신 측 제외 규칙은 [로그 연결 지침](integration/LOGS.md)을 따른다. NAS에서는 DB·파일·로그 영속 볼륨을 모두 volume2로 연결한다. [수동 배포 안내](NAS_DEPLOYMENT.md) 참고.

| 구성 | 사용 버전 |
|---|---|
| Java | Temurin 21, 실행 이미지 `21-jre-alpine` |
| Gradle 빌더 | `9.7.1-jdk21` |
| Spring Boot | 4.1.1 |
| PostgreSQL | 17.11-alpine |
| Keycloak | 26.7.4 |
| Nginx | 1.30.5-alpine |
| 관리자 웹 | React 19.3.0, TypeScript 7.0.2, Vite 8.3.1, keycloak-js 26.2.4 |
| 웹 빌더 | Node 24-alpine, npm lockfile로 패키지 고정 |
| 환경 생성·HTTP 검증 도구 | Python 3.13-alpine, 표준 라이브러리만 사용 |

Java 21은 [Spring Boot 지원 범위](https://docs.spring.io/spring-boot/system-requirements.html)에 포함된다. PostgreSQL 17은 [지원 중인 계열](https://www.postgresql.org/support/versioning/)이며 [Keycloak 지원 DB](https://www.keycloak.org/server/db)에도 포함된다. Nginx 버전은 [공식 배포 목록](https://nginx.org/en/download.html)을 확인했다. Java·Python 태그는 패치 갱신을 받을 수 있는 계열 태그이므로 바이트 단위 재현성을 보장하지 않는다. 출시 이미지에는 대상 아키텍처와 digest를 기록해야 한다.

Gradle 9.x는 [Spring Boot Gradle 플러그인의 지원 범위](https://docs.spring.io/spring-boot/gradle-plugin/index.html)에 포함된다. Kotlin DSL은 빌드 설정에만 사용하며 애플리케이션은 Java다.

Keycloak은 [공식 컨테이너 빌드 방식](https://www.keycloak.org/server/containers)에 따라 DB·health·metrics 및 HTTP 경로를 빌드 시 설정하고 `start --optimized`로 실행한다. [관리 포트의 readiness](https://www.keycloak.org/observability/health)는 내부에서만 검사한다.

## 실행·설정 경계

- `compose.yml`: 이미지·환경변수·영속 볼륨으로 실행. 소스 파일이 없어도 구성 해석 가능.
- `compose.dev.yml`: 빌드 경로, 개발 DB 포트, 선택 실행하는 검증 컨테이너.
- `.env.example`: 비밀값 없는 설정 목록. 생성기는 기존 `.env`를 덮어쓰지 않는다.
- 자원 설정은 환경변수로 조정한다. JVM 최대 힙 기본값은 컨테이너 메모리의 60%이며 나머지 공간은 JVM의 힙 외 메모리 등에 사용한다. 전체 서버 메모리를 고정하지 않는다.
- 컨테이너 로그는 파일당 10MB, 3개로 회전한다. Spring 로그는 구조화 JSON이며 DB 접속 비밀번호는 출력하지 않는다.
- Nginx는 현재 접근 로그를 끈다. 인증 코드나 공유 비밀값이 query string으로 기록되지 않도록 업무 요청 추적 구현 때 로그 형식과 마스킹을 함께 정한다.
- readiness는 DB 연결도 확인한다. DB 장애 시 API readiness는 실패해야 한다. Compose health 실패 자체는 컨테이너를 자동 재시작하지 않는다.
- Nginx는 다른 API의 기동을 기다리지 않으며 Docker DNS로 연결 대상을 다시 찾는다. 서비스 하나의 시작 실패가 프록시 전체 기동을 막지 않게 한다. 없는 서비스의 경로는 아직 사용할 수 없다.
- 기본 바인딩은 127.0.0.1이고 Keycloak 외부 URL은 `http://localhost:30140/auth`다. 프록시는 전달 헤더를 덮어쓴다. NPM을 경유한 개발 도메인 HTTPS 연결·실제 접속 IP 전달·가짜 IP 거부를 검증했고 사용자도 LTE/5G의 공인 IP 표시를 확인했다. 신뢰 프록시와 포트 접근 제한을 함께 설정하며 운영 NAS로 이동할 때 다시 검증한다. [역방향 프록시 안내](REVERSE_PROXY.md)를 따른다.

DB 초기화는 빈 볼륨에서 한 번만 실행된다. 환경변수의 비밀번호를 바꾸어도 이미 생성된 계정의 비밀번호는 바뀌지 않는다. DB를 보존하면서 변경하려면 해당 계정의 비밀번호 변경과 서비스 설정 교체를 함께 진행해야 한다. Keycloak 최초 관리자 비밀번호도 환경변수만으로 재설정되지 않는다.

## 검증 범위

`smoke`는 4개 상태 URL, 비공개 진단 경로 차단, Keycloak discovery의 고정 issuer와 전달 헤더 위조 방지를 확인한다. `db-check`는 서비스 계정 4개의 자기 DB·스키마 접근과 다른 DB 접속 12건의 거부를 확인한다.

프로젝트별 실제 사용자 분리, 키 폐기, 개발 Mock 로그인과 운영 모드 차단 검사는 [프로젝트·인증 API](PROJECT_API.md)를 참고한다. 실제 소셜 제공자·파일·알림·웹훅·에디터, 백업 복원·부하·Windows 및 Linux amd64 실기동은 아직 별도 검증 대상이다.

관리자 웹은 별도 정적 웹 이미지다. Nginx 30140의 `/`와 `/assets/`에서 프록시하고 호스트 포트·DB 자격증명을 추가하지 않는다. 공개 로그인 설정은 project-service의 `/api/v1/config`에서 읽으므로 운영 주소를 바꿀 때 웹 이미지를 다시 빌드하지 않는다. `.env`의 `PLATFORM_WEB_URL`을 수정한 뒤 인증 초기화를 재실행한다. Node 이미지도 계열 태그이며 출시 시 digest 기록 대상이다.

## 소셜 제공자 확장

외부 소셜 콜백은 `/auth/social/{naver|kakao|google}/callback`으로 고정한다. Nginx는 이를 기존 master realm의 `platform-social` REST 확장으로 전달한다. master는 라우팅 리소스를 호스팅할 뿐 회원·소셜 연결·로그인 세션을 저장하는 공통 broker가 아니다. 사용자 인증은 원래 프로젝트 realm에서 완료한다. 자세한 실행·검증 방법은 [공통 소셜 로그인](SOCIAL_LOGIN.md)을 따른다.

구글·카카오는 각각 Keycloak 기본 Google·OIDC 구현을 상속한 `platform-google`·`platform-kakao`, 네이버는 `platform-naver` OAuth2 확장을 사용한다. 공통 콜백·토큰 교환 주소만 보완하고 기본 프로필·서명 검증을 재사용한다. 카카오 서명·issuer 검증과 고정 JWKS 주소를 적용하고 범위는 `openid`로 둔다. 실제 제공 프로필·동의 항목은 앱 설정에 따라 달라진다. [카카오 OIDC 메타데이터](https://kauth.kakao.com/.well-known/openid-configuration)

네이버 확장은 Keycloak이 OAuth 요청·state·코드 교환을 처리하게 하고, 고정된 네이버 프로필 API의 `resultcode`와 `response.id`를 검증해 사용자 식별자로 변환한다. Keycloak이 검증한 콜백의 state를 네이버 토큰 교환 요청에도 전달한다. 사용자명은 외부 ID의 SHA-256으로 만들며 변경 가능한 이메일로 식별하지 않는다. 이메일 미제공 시 Keycloak의 기본 첫 로그인 프로필 입력 정책을 따른다. [네이버 로그인 개발 가이드](https://developers.naver.com/docs/login/devguide/devguide.md)

확장은 별도 서비스가 아닌 Keycloak 이미지 안의 SPI JAR다. Docker 내부의 독립 Gradle Kotlin DSL 빌드로 단위 검사 후 JAR만 복사한다. Java 21·Gradle 9.7.1·Keycloak 의존성 26.7.4를 사용하며, Keycloak 이미지 버전을 바꾸면 확장 의존성·컴파일·기동·실제 로그인 호환성도 함께 검수한다. 추가 포트·DB·운영 소스 마운트는 없다. 표준 프로토콜 구현을 새로 복제하지 않는다.

소셜 제공자 사용 설정은 Keycloak 관리 API로 관리한다. 공통 Client ID·Secret 원본은 `.env`이며 Keycloak 컨테이너 환경변수에서 읽는다. 새 공통 설정에는 키를 realm DB에 복제하지 않는다. 프로젝트 서비스는 내부 REST 확장에서 값 없는 준비 여부만 읽는다. 플랫폼 DB에는 감사 이벤트만 기록한다. 비밀값을 프론트엔드 응답·로그에 포함하지 않는다. 현재는 설정 관리·확장 빌드·로컬 구성을 검증한 상태이며, 실제 제공자 로그인은 인증 정보를 받은 이후 검수한다.

가입·복구 정책도 Keycloak realm을 원본으로 사용하며 플랫폼에는 감사 이력만 저장한다. 일반 가입·콜백은 기존 프로젝트 DB 소유를 유지한다. 두 설정 변경 모두 같은 프로젝트 잠금으로 직렬화한다. 기존 realm에는 새 비밀번호 기본값을 소급 적용하지 않는다. NCP 이메일 발송과 개발 모의 수신함은 후속 구현 대상이다.
