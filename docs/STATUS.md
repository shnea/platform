# 작업 상태와 다음 작업

## 현재 단계

첫 구현 단위인 MSA 실행 기반을 작성했다. Gradle로 프로젝트·파일·알림 서비스 이미지를 각각 빌드하며, PostgreSQL·Keycloak·Nginx와 함께 실행한다. 업무 기능은 아직 구현하지 않았다.

## 완료한 내용

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

## 검증 결과

- macOS 호스트의 Linux arm64 Docker에서 Gradle 서비스 이미지 3개 빌드 성공. Java 소스 컴파일·실행 JAR 패키징을 확인했다. 현재 Java 단위 테스트는 없으며 Gradle의 test 작업은 NO-SOURCE다.
- Compose 설정 검사: 운영 구성에 build·소스 마운트가 없고 API·Keycloak·DB 호스트 포트 미노출, 자원 제한 조정 가능, 비밀값 누락 시 실패 확인.
- 환경 생성기: 비밀번호 6개가 서로 다름, 파일 권한 0600, 기존 파일 보존 확인.
- `docker compose -f compose.yml -f compose.dev.yml --profile test run --rm db-check`: 자기 DB와 스키마 접근 4건 성공, 타 서비스 DB 접속 12건 차단.
- `docker compose -f compose.yml -f compose.dev.yml --profile test run --rm smoke`: 상태 URL 4개, 외부 진단 경로 차단, 내부 Actuator 제한, Keycloak 고정 issuer·전달 헤더 위조 검사 통과.
- 새 플랫폼 DB만 중지한 뒤 `smoke python /checks/smoke.py --db-down`: Nginx는 정상, API readiness 3개는 HTTP 503으로 DB 장애 표시.
- DB 재기동 후 Compose의 6개 컨테이너가 모두 healthy로 복구되는 것을 확인했다.
- 첫 기동 중 확인한 Keycloak 빌드 시 경로 설정 누락을 수정했다. 개발 DB의 내부 네트워크만으로 포트가 열리지 않는 문제는 개발 전용 브리지 추가로 수정하고 127.0.0.1:30141 배정을 확인했다.
- Gradle 빌더의 linux/amd64·linux/arm64 이미지 manifest 확인. Windows 개발·Linux amd64 실제 기동과 운영 배포는 아직 검증하지 않았다.

## 다음 작업

1. **프로젝트·인증·개발 모드.** 프로젝트와 환경 데이터 모델·DB 마이그레이션, 플랫폼 관리자 인증과 권한, 프로젝트별 Keycloak realm 관리 연동을 먼저 구현한다. 업무 API를 열 때 인증·프로젝트 권한 검증을 반드시 함께 구현한다. 개발 Mock 모드는 현재 미구현이다.
2. **파일.** 5GB·멀티·분할·일시정지·재개, 파일 URL·공개/보호 공유, 미리보기·뷰어, 보존 코드와 실제 삭제.
3. **알림·웹훅.** NCP 이메일·SMS를 포함한 6개 채널, 모의 수신함, 재시도·중복 방지·서명 전달.
4. **에디터·뷰어·관리 화면.** Markdown 붙여넣기·공통 문서 형식·다중 프레임워크 배포·호스트 저장·업로더 교체와 통합 관리 UI. 관리자 UI는 각 서비스 구현과 함께 필요한 부분부터 만든다.
5. **첫 출시 검증.** 전체 요구사항·실제 외부 연동·지원 OS·부하·백업 복원·배포 절차.

이 순서는 첫 출시 범위를 줄이는 결정이 아니다. 프로젝트별 계정 분리·5GB 업로드·6채널 알림·에디터 등의 제품 기능을 구현 완료로 간주하지 않는다.

## 남은 상세 정보

- 프로젝트 내 개발·운영 계정 분리 모델, 가입·복구 방식과 소셜 앱·콜백 정보.
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

검증 후 개발 컨테이너 6개는 실행 상태로 둔다. 기본 주소는 http://localhost:30140 이며 현재는 JSON 상태 응답과 Keycloak 기본 관리 화면만 제공한다.

실행: `docker compose -f compose.yml -f compose.dev.yml up -d --build --wait --wait-timeout 300`.
중지: `docker compose -f compose.yml -f compose.dev.yml down`. DB 볼륨은 보존한다.

최신 커밋·푸시 여부는 Git에서 확인한다. `ahead`이면 미푸시 커밋을 확인해 검증 후 푸시한다. 원격 분기가 있으면 사용자 변경을 보존한다.
