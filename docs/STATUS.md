# 작업 상태와 다음 작업

## 현재 단계

MSA 실행 기반 위에 프로젝트·환경 관리와 인증 API의 첫 구현을 마쳤다. 관리자 JWT 보호, 별도 realm 생성·복구, 서버 API 키, 감사 이벤트, 개발 소셜 Mock 로그인까지 제공한다. 관리자 UI·실제 소셜 제공자·파일·알림·에디터 업무 기능은 아직 없다.

## 이번 작업에서 완료한 내용

- 프로젝트·환경 등록·목록, Keycloak realm 자동 생성 및 실패 상태 보존·재시도 API.
- Flyway 마이그레이션으로 프로젝트·환경·API 키 해시·감사 이벤트 테이블 생성. 자기 DB만 사용.
- 플랫폼 관리자 전용 realm을 개발·운영별로 분리하고 JWT 서명·issuer·audience·역할 검증 적용.
- 초기화 도구만 master 비밀번호를 사용. 애플리케이션은 create-realm 권한의 서비스 계정으로 자기 realm을 관리.
- 정확한 콜백 URL 등록·PKCE(S256) 클라이언트·환경별 일반 가입 허용 설정.
- 환경별 무작위 서버 API 키 발급·해시 저장·90일 만료·폐기와 연동 소속 조회.
- DEV 전용 카카오·네이버·구글 Mock 로그인. 내부 Keycloak 토큰 발급, 반복 사용자 유지, 프로젝트·환경 분리. 외부 소셜 호출 없음.
- 운영 모드 Mock 컨트롤러 미등록, PROD 환경의 API 키로 Mock 호출 차단.
- 인증 초기화도 tools 이미지로 패키징해 운영용 Compose와 환경변수만으로 실행 가능. 재실행 검증 통과.
- 기존 .env 값을 보존하는 `scripts/init-env.py --upgrade`, 개발 초기화·API 안내·통합 검증 스크립트.

API·사용법과 검사 명령은 [프로젝트·인증 API](PROJECT_API.md)에 있다.

## 이번 작업의 검증 결과

- Docker 안의 Gradle 빌드와 JUnit 검사 3개 통과: 콜백 안전성, dev/prod Mock 컨트롤러 등록 조건.
- `project-check` 통과: 무인증·변조 토큰 거부, 생성·중복 제한, 콜백 검증, realm 재시도, API 키 소속·변조·폐기, 감사 이벤트.
- 실제 Keycloak에 프로젝트별 동일 사용자명을 만들고 별도 사용자 ID·비밀번호·issuer를 확인. 다른 프로젝트 토큰으로 관리자 API 접근 불가.
- 세 Mock 제공자와 반복 로그인, 프로젝트별 별도 사용자, 폐기 키 거부, PROD 환경 Mock 호출 거부 통과.
- 임시 prod 모드 컨테이너에 개발 공개키를 의도적으로 제공한 조건에서도 개발 JWT를 401로 거부하고 Mock 경로는 404. 검사 후 컨테이너 정리.
- 잘못된 provisioner secret을 넣은 임시 컨테이너에서 환경 FAILED를 기록하고, 정상 서비스에서 동일 ID·realm으로 READY 복구. 검사 후 컨테이너 정리.
- 기존 HTTP smoke와 DB 격리 검사 통과. 관리자 API 추가 후 내부 진단 경로의 거부 상태를 401로 반영.
- 초기 Keycloak 관리자 필수 프로필, Mock 테스트 이메일 길이, 사용자 프로필 소유 표식 보존 문제를 실제 기동 중 확인해 수정했다.
- 검사 데이터는 `check-...`, `recovery-...` 프로젝트와 개발 realm으로 남아 있다. 테스트가 중간에 실패한 회차의 테스트 클라이언트·계정도 일부 남을 수 있다. 실제 서비스 사용자 데이터는 없다.

## 이전에 완료한 실행 기반

- 루트 요구사항 요약과 상세 문서, 작업 재개·한글 커밋·푸시 규칙.
- Java 21·Spring Boot 4.1.1·Gradle 9.7.1 Kotlin DSL 빌드. 사용자 요청에 따라 빌드 도구는 Gradle로 확정했다.
- 이미지 실행 전용 `compose.yml`과 개발 빌드·검증용 `compose.dev.yml`.
- Nginx 30140, 개발 DB의 로컬 전용 30141, 서비스별 CPU·메모리 설정과 로그 회전.
- PostgreSQL 17.11 안에 프로젝트·파일·알림·Keycloak 전용 DB와 개별 계정. 다른 DB의 CONNECT 권한 차단.
- Keycloak 26.7.4의 최적화 이미지·DB 초기화·상태 검사와 `/auth` 프록시.
- 실제 DB 연결을 포함하는 세 API의 readiness. 업무 엔드포인트는 아직 없고 외부 Actuator 진단 경로는 차단.
- 비밀값 없는 환경 예시, 기존 `.env`를 덮어쓰지 않는 무작위 비밀번호 생성기.
- Docker 안에서 실행하는 HTTP·DB 격리 검사와 개발 실행 안내.

구성·버전·서비스 경계는 [실행 기반 문서](ARCHITECTURE.md), 실행 명령은 [README](../README.md)에 있다.

## 실행 기반 검증 이력

- macOS 호스트의 Linux arm64 Docker에서 Gradle 서비스 이미지 3개 빌드 성공. Java 소스 컴파일·실행 JAR 패키징을 확인했다. 당시에는 Java 테스트가 없어 test 작업이 NO-SOURCE였다. 현재는 위의 프로젝트 서비스 검사를 추가했다.
- Compose 설정 검사: 운영 구성에 build·소스 마운트가 없고 API·Keycloak·DB 호스트 포트 미노출, 자원 제한 조정 가능, 비밀값 누락 시 실패 확인.
- 환경 생성기: 비밀번호 6개가 서로 다름, 파일 권한 0600, 기존 파일 보존 확인.
- `docker compose -f compose.yml -f compose.dev.yml --profile test run --rm db-check`: 자기 DB와 스키마 접근 4건 성공, 타 서비스 DB 접속 12건 차단.
- `docker compose -f compose.yml -f compose.dev.yml --profile test run --rm smoke`: 상태 URL 4개, 외부 진단 경로 차단, 내부 Actuator 제한, Keycloak 고정 issuer·전달 헤더 위조 검사 통과.
- 새 플랫폼 DB만 중지한 뒤 `smoke python /checks/smoke.py --db-down`: Nginx는 정상, API readiness 3개는 HTTP 503으로 DB 장애 표시.
- DB 재기동 후 Compose의 6개 컨테이너가 모두 healthy로 복구되는 것을 확인했다.
- 첫 기동 중 확인한 Keycloak 빌드 시 경로 설정 누락을 수정했다. 개발 DB의 내부 네트워크만으로 포트가 열리지 않는 문제는 개발 전용 브리지 추가로 수정하고 127.0.0.1:30141 배정을 확인했다.
- Gradle 빌더의 linux/amd64·linux/arm64 이미지 manifest 확인. Windows 개발·Linux amd64 실제 기동과 운영 배포는 아직 검증하지 않았다.

## 다음 작업

1. **프로젝트·인증 관리 완성.** 프로젝트 중지·재개와 설정 변경, 키별 기능 권한·목록, 브라우저 관리자 로그인·관리 UI, 소셜별 설정과 가입·복구 정책을 이어서 구현한다. Mock 선택 화면·실패 시나리오와 발송 Mock은 아직 없다. 실제 카카오·네이버·구글 연동은 외부 앱 정보가 필요하다.
2. **파일.** 5GB·멀티·분할·일시정지·재개, 파일 URL·공개/보호 공유, 미리보기·뷰어, 보존 코드와 실제 삭제.
3. **알림·웹훅.** NCP 이메일·SMS를 포함한 6개 채널, 모의 수신함, 재시도·중복 방지·서명 전달.
4. **에디터·뷰어·관리 화면.** Markdown 붙여넣기·공통 문서 형식·다중 프레임워크 배포·호스트 저장·업로더 교체와 통합 관리 UI. 관리자 UI는 각 서비스 구현과 함께 필요한 부분부터 만든다.
5. **첫 출시 검증.** 전체 요구사항·실제 외부 연동·지원 OS·부하·백업 복원·배포 절차.

이 순서는 첫 출시 범위를 줄이는 결정이 아니다. 실제 소셜 로그인·전체 가입 정책·5GB 업로드·6채널 알림·에디터 등 전체 제품 기능을 완료로 간주하지 않는다.

## 남은 상세 정보

- 현재 프로젝트의 환경마다 별도 realm으로 계정을 분리한다. 추가 가입·복구 방식과 소셜 앱·콜백 정보는 아직 필요하다.
- NCP 발신 설정, 카카오 메시지 종류·제공 업체, Telegram 봇, 웹 푸시 지원 범위. 실제 키는 문서에 기록하지 않는다.
- 미리보기 형식·OG 공개 정보·보존 기간 계산·삭제 유예·업로드 세션 보관 기간.
- 에디터 패키지 이름·배포 위치·지원 프레임워크 버전.
- 운영 도메인·TLS·레지스트리 게시·백업 저장소·부하 목표. 현재 이미지는 로컬 빌드만 했으며 레지스트리에는 게시하지 않았다.

## 재개와 실행 환경

```sh
git status --short --branch
git log -5 --oneline
git fetch origin
docker compose -f compose.yml -f compose.dev.yml ps
```

개발 Compose 프로젝트 이름은 `.env`의 `shnea-platform-dev`다. 다른 기존 컨테이너와 볼륨을 사용하지 않는다. `.env`는 Git에서 제외되므로 다른 PC에서는 생성기를 실행한다. DB 데이터가 이미 있는 환경에서는 `.env`만 새로 생성하지 말고 기존 자격증명과 일치시켜야 한다.

검증 후 개발 컨테이너 6개는 실행 상태로 둔다. 기본 주소는 http://localhost:30140 이며 프로젝트·인증 API와 JSON 상태 응답, Keycloak 기본 관리 화면을 제공한다. 통합 관리자 UI는 아직 없다.

실행: `docker compose -f compose.yml -f compose.dev.yml up -d --build --wait --wait-timeout 300`.
중지: `docker compose -f compose.yml -f compose.dev.yml down`. DB 볼륨은 보존한다.

최신 커밋·푸시 여부는 Git에서 확인한다. `ahead`이면 미푸시 커밋을 확인해 검증 후 푸시한다. 원격 분기가 있으면 사용자 변경을 보존한다.
