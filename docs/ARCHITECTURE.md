# MSA 실행 기반

이 문서는 첫 구현 단위의 실제 구성을 설명한다. 전체 기능 범위는 [요구사항](REQUIREMENTS.md)에 있다.

## 서비스와 데이터 소유권

| 서비스 | 이미지 접미사 | DB·접속 계정 | 현재 구현 |
|---|---|---|---|
| Nginx | nginx | 없음 | 30140 진입·상태 라우팅·Keycloak 프록시 |
| 프로젝트 | project-service | platform_project | Spring Boot 기동·DB 상태 |
| 파일 | file-service | platform_file | Spring Boot 기동·DB 상태 |
| 알림 | notification-service | platform_notification | Spring Boot 기동·DB 상태 |
| Keycloak | keycloak | platform_identity | PostgreSQL 기반 인증 엔진 기동 |
| PostgreSQL | postgres | platform_admin(초기화·운영 전용) | 네 DB와 개별 소유자 생성 |

이미지는 모두 `register.shnea.kr/platform-접미사:IMAGE_TAG`로 빌드한다. 현재 로컬 태그는 `0.1.0-dev`다. 레지스트리에 게시한 상태는 아니다.

하나의 PostgreSQL 서버에 별도 DB와 비밀번호를 둔다. 서비스 계정에는 다른 DB의 CONNECT 권한이 없다. DB 관리자와 Docker 호스트 관리자는 모든 DB를 관리할 수 있으므로 물리적인 장애·관리자 격리는 아니다. 서비스에는 자기 비밀번호만 전달한다. Keycloak DB에 접근하는 신규 업무 코드는 만들지 않는다.

`app` 네트워크는 프록시·API·Keycloak이 사용하고, `database`는 외부 경로가 없는 내부 네트워크다. 운영 구성에서 DB는 후자에만 연결한다. 개발용 Compose는 DB에 `db-access` 브리지를 추가해 호스트 포트 30141을 127.0.0.1에 연다. API와 Keycloak에는 호스트 포트가 없다.

각 API는 개별 Gradle 하위 프로젝트, 프로세스, 이미지다. Kotlin DSL로 빌드하고 Gradle은 Docker 이미지에 고정한다. 공통 런타임 모듈이나 서비스 간 DB 조인은 없다. 현재 컨트롤러는 없고 Actuator의 health만 활성화했다. 업무 API를 추가할 때 인증·프로젝트 권한 검증을 함께 구현해야 한다.

## 버전 선택

| 구성 | 사용 버전 |
|---|---|
| Java | Temurin 21, 실행 이미지 `21-jre-alpine` |
| Gradle 빌더 | `9.7.1-jdk21` |
| Spring Boot | 4.1.1 |
| PostgreSQL | 17.11-alpine |
| Keycloak | 26.7.4 |
| Nginx | 1.30.5-alpine |
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
- 기본 바인딩은 127.0.0.1이고 Keycloak 외부 URL은 `http://localhost:30140/auth`다. 프록시는 전달 헤더를 덮어쓴다. 외부 TLS 프록시 뒤의 운영 연결은 아직 검증하지 않았으며 HTTP 개발 설정을 그대로 공개하지 않는다.

DB 초기화는 빈 볼륨에서 한 번만 실행된다. 환경변수의 비밀번호를 바꾸어도 이미 생성된 계정의 비밀번호는 바뀌지 않는다. DB를 보존하면서 변경하려면 해당 계정의 비밀번호 변경과 서비스 설정 교체를 함께 진행해야 한다. Keycloak 최초 관리자 비밀번호도 환경변수만으로 재설정되지 않는다.

## 검증 범위

`smoke`는 4개 상태 URL, 비공개 진단 경로 차단, Keycloak discovery의 고정 issuer와 전달 헤더 위조 방지를 확인한다. `db-check`는 서비스 계정 4개의 자기 DB·스키마 접근과 다른 DB 접속 12건의 거부를 확인한다.

이는 기반 검증이다. 프로젝트별 사용자 분리, Mock 운영 격리, 실제 로그인·파일·알림·웹훅·에디터 기능, 백업 복원·부하·Windows 및 Linux amd64 실기동은 별도 검증 대상이다.
