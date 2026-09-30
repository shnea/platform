# 공통 서비스 플랫폼

**외부 프로젝트의 AI에게 [서비스 연결 지침](https://platform.shnea.kr/integrations/SERVICE_INTEGRATION.md)을 전달한다.** 기능별 문서·OpenAPI·예제·패키지 URL과 인증·필수 규칙만 담은 진입점이다. 모든 자료 링크는 관리자 로그인 없이 읽을 수 있고 실제 API 호출에는 환경별 인증이 필요하다. [저장소 원본](docs/SERVICE_INTEGRATION.md)과 공개 자료는 관리자 이미지 빌드 시 함께 갱신한다.

관리자 **에디터** 메뉴에서 공통 에디터의 편집/읽기, `/` 종류별 메뉴, 표 가장자리 조작, Markdown 붙여넣기 선택과 JSON 입출력을 확인할 수 있다. 본문은 메모리에만 유지한다. 파일·영상 연결과 React/Vue·일반 JS/JSP 예제를 제공하며 **개발자 센터 → 에디터 연동**에서 실행 예제와 지침을 확인한다. 실제 호스트의 저장·인증·첨부 연결은 별도 검수 대상이다. [에디터 연동 문서](docs/EDITOR.md)에서 현재 범위를 확인한다.

여러 프로젝트가 로그인, 파일, 알림, 블록 에디터·뷰어를 선택해 사용하고 한 관리자 화면에서 운영하는 플랫폼이다. 기존 서비스 코드를 재사용하지 않고 새로 구현하며, 앞으로 추가되는 프로젝트에 적용한다.

프로젝트·환경 관리, 중지·재개, Keycloak 브라우저 관리자 로그인, 서버 API 키, 감사 이력과 개발 소셜 Mock 로그인 API를 구현했다. 환경별 회원 조회·상태·세션과 가입·복구 정책을 관리할 수 있다. 인증 이메일의 NCP 발송 경로와 개발용 모의 수신함을 연결했고, 모의 메일로 실제 이메일 인증·비밀번호 재설정 흐름을 검증했다. 이번 NCP 어댑터의 실제 외부 수신 검수는 별도다. 관리자 화면은 어두운 테마가 기본이며 밝은 테마로 전환할 수 있다. 구글·카카오·네이버 설정·공통 콜백과 세 제공자의 실제 계정 연결을 검수했다. 파일은 서버 API 기반 분할·재개 업로드, 원본 다운로드·공개 범위·삭제까지 구현했으며 관리자 파일 메뉴의 멀티·드래그 업로드, 일시정지·재개와 파일 관리도 제공한다. 저장 목록의 썸네일·문서·영상 보기와 URL, 보존 정책·자동 정리·동일 내용 파일 확인도 제공한다. HLS 자동·360p·720p·1080p 변환/재생과 시간 이동도 제공한다. 비밀번호 공유·공개 공유 카드와 에디터/뷰어 연결도 제공한다. 이용자 업로드는 호스트 서버를 통하며 일반 알림 업무 API·외부 웹훅·공통 로그는 후속 단계다. [프로젝트·인증 API](docs/PROJECT_API.md), [파일 서비스](docs/FILES.md), [인증 이메일 안내](docs/IDENTITY_EMAIL.md)를 참고한다.

## 개발 실행

관리자 화면의 DEV 환경에서는 **개발 로그인 테스트**로 세 소셜 제공자의 성공·취소·동의 거부·장애를 재현할 수 있다. 외부 소셜 앱 자격증명 없이 실행하며 이용자 토큰은 화면에 노출하지 않는다.

Docker와 Compose 2.24.4 이상, Dotenvx 2.24.0을 준비한다. Java·Gradle·Node 빌드는 이미지 안에서 실행한다. Windows PowerShell 또는 macOS의 PowerShell 7에서 저장소 루트를 기준으로 실행한다. [도구·복호화 키 준비](docs/NAS_DEPLOYMENT.md)를 먼저 따른다.

```sh
./scripts/dev.ps1
```

Git에 포함된 암호화 `.env.dev`와 별도 전달받은 `.env.keys`를 사용한다. 기존 비밀번호·데이터를 재생성하지 않는다. 복호화 키는 Git에 포함하지 않는다.

환경은 `.env.dev`·`.env.prod` 두 개다. `./scripts/release.ps1`은 운영 키 없이 이미지 8개를 빌드·게시하고 `sudo sh scripts/deploy.sh <SHA 12자리>`는 NAS에서 Pull·기동한다. 운영 Compose 하나와 개발 override 하나, 격리 테스트 Compose 하나를 사용한다. `identity-setup`은 Keycloak 준비 후 자동 실행되며 `Exited (0)`이 정상이다. [배포 안내](docs/NAS_DEPLOYMENT.md)를 따른다.

| 주소 | 현재 동작 |
|---|---|
| http://localhost:30140/ | 플랫폼 관리자 화면 |
| http://localhost:30140/healthz | Nginx 상태 |
| http://localhost:30140/api/projects/health | 프로젝트 서비스와 DB 연결 상태 |
| http://localhost:30140/api/files/health | 파일 서비스와 DB 연결 상태 |
| http://localhost:30140/api/notifications/health | 알림 서비스와 DB 연결 상태 |
| http://localhost:30140/auth/admin/ | Keycloak 기본 관리 화면 |

Keycloak의 최초 관리자 ID와 비밀번호는 개발 환경의 `KEYCLOAK_ADMIN`, `KEYCLOAK_ADMIN_PASSWORD`다. 플랫폼 관리자 화면은 별도 계정 `admin`과 `PLATFORM_ADMIN_PASSWORD`로 로그인한다. 값은 암호화 `.env.dev`에서 관리한다. 바인딩은 해당 환경 설정을 따르며 관리자 주소는 `PLATFORM_WEB_URL`의 정확한 origin과 일치해야 한다. 주소 변경 후 `identity-setup`을 다시 실행한다. 운영은 HTTPS 주소가 필요하다.

운영 도메인은 `platform.shnea.kr`이다. TLS 배포 시 `PLATFORM_MODE=prod`, `PLATFORM_WEB_URL=https://platform.shnea.kr`, `KEYCLOAK_PUBLIC_URL=https://platform.shnea.kr/auth`로 설정하고 `identity-setup`을 실행한다. 관리자 로그인·로그아웃 콜백은 `https://platform.shnea.kr/`로 등록된다. 서비스 Nginx 진입 포트는 `30140`을 유지한다. DNS·TLS 종료 및 신뢰할 프록시의 전달 헤더 설정은 운영 배포 단계에서 적용·검증한다. 현재 로컬 개발 설정은 변경하지 않는다.

## 검증과 중지

```sh
./scripts/dev.ps1 --profile test run --rm smoke
./scripts/dev.ps1 --profile test run --rm db-check
./scripts/dev.ps1 --profile test run --rm project-check
./scripts/dev.ps1 --profile test run --rm project-check python /checks/check-lifecycle.py
./scripts/dev.ps1 --profile test run --rm project-check python /checks/check-mock.py
./scripts/dev.ps1 --profile test run --rm project-check python /checks/check-members.py
./scripts/dev.ps1 ps
./scripts/dev.ps1 logs --tail 80 project-service
./scripts/dev.ps1 down
```

검증 명령은 실행 중인 스택을 대상으로 한다. `down`은 DB 볼륨을 보존한다. DB 비밀번호와 초기화 SQL은 빈 볼륨의 첫 시작에만 적용되므로, 기존 DB의 비밀번호는 `.env.dev` / `.env.prod` 변경만으로 바뀌지 않는다.

서비스 하나만 수정했다면 `./scripts/dev.ps1 up -d --build --no-deps project-service`로 다시 빌드한다. CPU·메모리 설정은 `.env.dev` / `.env.prod`에서 바꾸고 `up -d --no-build`로 컨테이너를 재생성한다.

운영용 `compose.yml`에는 빌드 경로나 소스 마운트가 없다. 운영 배포 전 이미지 게시, Linux 대상 아키텍처, TLS와 도메인, 관리자 보호, 백업·복원 검증을 완료해야 한다. 현재 개발 이미지는 운영 출시본이 아니다. 구성과 경계는 [실행 기반 문서](docs/ARCHITECTURE.md)를 참고한다.

NAS 신규 설치는 [NAS 배포 안내](docs/NAS_DEPLOYMENT.md)를 따른다. 설정은 `${NAS_DEPLOY_PATH}`, DB·파일·로그 데이터는 `${PLATFORM_DATA_ROOT}` 아래에 저장한다. 운영 배포는 사용자 승인 범위에서 수행하며 개발 데이터는 이전하지 않는다.

외부 프로젝트의 [Job 워커 연결](docs/integration/JOBS.md)과 [공통 로그 연결](docs/integration/LOGS.md)은 서버 API 키로 사용한다. Job은 호스트 워커가 실행하며, 로그는 내부 Loki에 환경별로 저장한다. 관리자 비동기 작업 탭과 독립 로그 메뉴에서 확인한다.

관리자 화면 수정 후에는 `./scripts/dev.ps1 up -d --build --no-deps admin-web`을 실행한다. 이미지 빌드 과정에서 `npm ci`, TypeScript 검사와 Vite 빌드를 실행한다. 로그인·설정·모바일 수동 검수 순서는 [관리자 화면 안내](docs/ADMIN_WEB.md)에 있다.

NPM·NAS 뒤에서 접속 기기의 실제 IP를 표시하려면 [역방향 프록시 안내](docs/REVERSE_PROXY.md)의 헤더 신뢰·포트 접근 제한을 먼저 확인한다.

## GitHub 자동 배포

`main`에 push하면 GitHub Actions가 검사 → 이미지 빌드·게시 → `NAS_SSH_HOST:NAS_SSH_PORT` SSH 배포를 실행한다. NAS에는 Runner나 소스 빌드를 추가하지 않는다. [Actions 결과](https://github.com/shnea/platform/actions)에서 `verify / release / deploy` 모두 성공했는지 확인한다. 자동 배포 중 같은 커밋의 수동 release를 중복 실행하지 않는다.

운영 폴더의 **`운영안내.html`**을 열면 전체 흐름, 상태·로그 확인, 실패·롤백 절차를 볼 수 있다. 자세한 설명은 [CI/CD 안내](docs/CICD.md)에 있다. 이전 이관 파일은 NAS `platform_tmp`에 보관하고 실제 데이터는 `${PLATFORM_DATA_ROOT}`에 유지한다.

## 작업을 이어갈 때

다른 PC에서는 [PC 이동·작업 인계 안내](docs/HANDOFF.md)의 복제·초기화 절차를 따른다.

1. [프로젝트 작업 규칙](AGENTS.md)을 읽는다.
2. [핵심 요구사항](REQUIREMENTS.md)과 [현재 상태와 다음 작업](docs/STATUS.md)을 확인한다.
3. [상세 요구사항](docs/REQUIREMENTS.md)의 관련 기능·완료 조건을 확인한다.
4. Git 작업 트리·현재 브랜치·원격 상태를 확인한 뒤 다음 미완료 작업을 진행한다.

## 확정된 기반

- 인증: Keycloak, 프로젝트별 회원 분리·가입 방식 설정.
- 구조: MSA, Docker 기반 개발과 운영, Compose 배포.
- 외부 진입: Nginx `30140`; DB 외부 접근이 필요하면 `30141`; 추가 포트는 `30142`부터 순차 배정.
- 이미지: `registry.shnea.kr/platform-이미지명:태그`.
- 에디터: 첫 출시부터 React·Vue 패키지 및 JSP·일반 JS용 자산 제공. 본문은 각 서비스가 저장.
- Markdown 문서 붙여넣기를 블록·서식으로 변환하고 에디터·뷰어에서 올바르게 렌더링.
- 파일: 최대 5GB, 멀티·드래그·분할·재개 업로드, 기본 공개와 별도 비공개/비밀번호 공유.
- 보존: 고정 `default`·`tmp`·`영구`와 사용자 등록 코드. 코드 기간 변경은 기존 파일에도 적용.
- 알림: 이메일·앱 내·웹 푸시·SMS·카카오·Telegram. 이메일·SMS는 NCP.
- Git: https://git.shnea.kr/shnea/platform.git

## 변경 기록

완료한 작업 단위마다 검증하고 한글 커밋 메시지로 푸시한다. 작업을 넘기거나 중단할 때 현재 상태와 다음 작업을 함께 갱신한다. 기능별 상세 결정은 요구사항 문서를 기준으로 한다.
