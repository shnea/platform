# 다른 PC에서 이어서 개발하기

## 저장소 받기

Git과 Docker·Compose를 준비한다. Windows에서는 Docker Desktop의 Linux 컨테이너를 사용한다. 호스트에 Java·Gradle·Node를 설치할 필요는 없다. 저장소 접근에 필요한 Git 계정은 새 PC에서 설정한다.

처음 받는 PC:

```sh
git clone https://github.com/shnea/platform.git
cd platform
```

이미 복제한 PC에서는 로컬 변경을 먼저 확인하고 업데이트한다. 변경을 덮어쓰거나 강제 초기화하지 않는다.

```sh
git status --short --branch
git pull --ff-only origin main
```

## 새 개발 환경 실행

Docker Compose 2.24.4 이상과 Dotenvx 2.24.0을 준비하고 기존 환경의 복호화 키를 별도 보안 경로로 받는다. Windows PowerShell 또는 macOS의 PowerShell 7에서 저장소 루트를 기준으로 실행한다. [도구·키 준비](NAS_DEPLOYMENT.md)를 따른다.

아래는 빈 개발 환경의 시작 순서다. 기존 개발 데이터를 이어받는 경우 `config --quiet`까지 실행하고 [데이터 복원](#기존-데이터까지-옮길-때)을 마친 뒤 기동한다.

```sh
./scripts/dev.ps1 config --quiet
./scripts/dev.ps1 up -d --build --wait --wait-timeout 300
./scripts/dev.ps1 --profile setup run --rm --build identity-setup
./scripts/dev.ps1 ps
```

암호화된 `.env.dev`는 Git으로 전달하고 `.env.keys`는 별도 보관한다. 기존 환경을 재생성하지 않는다. 설정은 Dotenvx로 수정하며 DB 비밀번호 변경은 기존 DB 계정과 함께 처리해야 한다.

관리자 화면은 http://localhost:30140/ 이다. ID는 `admin`, 비밀번호는 새 PC의 `.env.dev` / `.env.prod`에 있는 `PLATFORM_ADMIN_PASSWORD`를 사용한다. 최초 설정 후 프로젝트·환경을 생성한다. 지금까지 만든 테스트 프로젝트는 기존 PC의 DB에만 있으므로 새 PC에는 자동으로 나타나지 않는다.

새 PC에서도 dev 명령으로 직접 빌드한다. 첫 빌드에는 기반 이미지·의존성 다운로드가 필요하다. 운영 이미지 게시·배포는 [release/deploy 안내](NAS_DEPLOYMENT.md)를 따른다. 이번 스크립트 변경의 macOS 실기동·부하·백업 복원 검증은 별도다.

## Git에 들어 있는 것과 별도 항목

- 코드, Gradle 설정, npm 잠금 파일, Dockerfile, Compose, `.env.example`, 초기화·검사 스크립트, 요구사항·상태·디자인 문서는 저장소에 포함된다.
- 암호화된 `.env.dev`·`.env.prod`는 Git에 포함한다. 평문 비밀값·복호화 키·Docker DB 볼륨·빌드 캐시·이미지·화면 캡처는 포함하지 않는다. 기존 데이터까지 이어야 한다면 DB 백업과 복호화 키를 별도 보안 경로로 옮긴다. 개발 데이터를 운영으로 이전하지 않는다.
- 개인 Codex 스킬과 브라우저 도구는 PC별 설치 항목이다. `/private/tmp`의 이전 검사용 임시 도구나 기존 브라우저 세션에 의존하지 않는다. 새 PC에 설치된 도구를 확인해 사용한다.

## 기존 데이터까지 옮길 때

| 대상 | 준비물 | 인수 확인 |
| --- | --- | --- |
| 개발 PC | 저장소, Git 접근 권한, 프로젝트 루트에 둘 기존 `.env.keys`, OS에 맞는 Dotenvx | `./scripts/dev.ps1 config --quiet` 성공 후 개발 스택을 기동하고 관리자 로그인 확인 |
| 개발 데이터도 필요한 PC | 개발 Compose 프로젝트의 `postgres-data`, `file-data`, 로그 보존 시 `log-data` 볼륨 | 최초 기동 전에 복원하고 기존 프로젝트와 파일 ID를 이용해 원본·미리보기를 확인 |
| 운영 NAS도 이전 | 운영 키만 담은 `.env.keys`, 기존 릴리스 태그·digest와 배포 이력, 운영 DB·파일·로그 백업, Registry pull 권한 | 같은 릴리스로 복원한 뒤 로그인, 기존 파일 조회, 비공개 접근 차단, Loki readiness를 확인 |

DB에는 프로젝트·계정과 파일 메타데이터가 있고, 원본·미리보기·HLS는 파일 저장소에 있다. 두 저장소의 백업 시점을 맞춘다. PostgreSQL 데이터 디렉터리를 직접 옮긴다면 쓰기를 중지하고 DB를 정상 종료한 뒤 같은 PostgreSQL 주 버전에서 복원한다. 논리 백업을 사용하면 서비스 DB 전체와 역할·권한도 포함한다. 복원 확인까지 이전 사본을 보관한다.

운영 데이터 위치는 `${PLATFORM_DATA_ROOT}/postgres`, `files`, `loki`이며 배포 설정과 `.deploy` 이력은 `${NAS_DEPLOY_PATH}` 아래에 있다. DNS·TLS·역방향 프록시, 소셜 로그인 콜백과 뇌대리 웹훅 수신 주소를 새 대상에 맞춰 확인한다. 구·신 서버가 같은 운영 작업을 동시에 소비하지 않도록 전환한다. [NAS 배포·롤백](NAS_DEPLOYMENT.md) · [프록시 설정](REVERSE_PROXY.md)

Git 호스팅까지 바꾸는 경우 새 저장소의 권한, Actions `prod` 환경, Registry·SSH Secrets와 `platform-deploy` 자체 호스팅 Runner를 준비한다. 실제 Secret 값은 별도의 보안 경로로 전달한다. 기존 NAS가 계속 배포를 받는 동안 새 Runner의 대상을 먼저 확인한다. [CI/CD 설정과 Secret 목록](CICD.md)

## 작업 재개

우선 [작업 규칙](../AGENTS.md), [핵심 요구사항](../REQUIREMENTS.md), [현재 상태](STATUS.md)를 읽는다.

최근 변경과 미검증 항목은 [현재 상태](STATUS.md) 상단에서 확인한다. 호스트 서비스 연동 계약은 [서비스 연결 지침](SERVICE_INTEGRATION.md), 관리자 계정 복구는 [관리자 보안](ADMIN_SECURITY.md)을 기준으로 한다.

작업은 작은 단위로 끝내고 변경에 필요한 검증만 수행한다. 완료 시 STATUS.md를 갱신하고 한글 커밋·푸시한다. 플랫폼과 무관한 브라우저 탭이나 검색 내역은 조회하지 않는다.

새 대화에서 사용할 요청 예:

> AGENTS.md, REQUIREMENTS.md, docs/STATUS.md, docs/HANDOFF.md를 읽고 현재 브랜치와 원격 상태를 확인해 줘. 개발 환경 구성을 검증하고 STATUS에 기록된 최근 작업에서 이어가 줘.

공통 소셜 키·콜백은 [설정 안내](SOCIAL_LOGIN.md), NCP 연결 검사와 미완료 범위는 [알림 설정](NOTIFICATION_SETUP.md)을 따른다. 실제 키·테스트 수신처는 `.env.dev`·`.env.prod` 안에서 암호화한다. Git에는 암호문만 전달하고 복호화 키는 별도 보관한다.
