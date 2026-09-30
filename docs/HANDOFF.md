# 다른 PC에서 이어서 개발하기

## 저장소 받기

Git과 Docker·Compose를 준비한다. Windows에서는 Docker Desktop의 Linux 컨테이너를 사용한다. 호스트에 Java·Gradle·Node를 설치할 필요는 없다. 저장소 접근에 필요한 Git 계정은 새 PC에서 설정한다.

처음 받는 PC:

```sh
git clone https://git.shnea.kr/shnea/platform.git
cd platform
```

이미 복제한 PC에서는 로컬 변경을 먼저 확인하고 업데이트한다. 변경을 덮어쓰거나 강제 초기화하지 않는다.

```sh
git status --short --branch
git pull --ff-only origin main
```

## 새 개발 환경 실행

Docker Compose 2.24.4 이상과 Dotenvx 2.24.0을 준비하고 기존 환경의 복호화 키를 별도 보안 경로로 받는다. Windows PowerShell 또는 macOS의 PowerShell 7에서 저장소 루트를 기준으로 실행한다. [도구·키 준비](NAS_DEPLOYMENT.md)를 따른다.

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

## 다음 작업

우선 [작업 규칙](../AGENTS.md), [핵심 요구사항](../REQUIREMENTS.md), [현재 상태](STATUS.md)를 읽는다.

환경별 회원·세션 관리와 NCP 인증 이메일 전달 경로·Keycloak 이메일 인증/복구·개발 모의 수신함을 구현했다. 2026-09-26 새 어댑터로 실제 인증·비밀번호 설정 메일 각 1건의 NCP 완료 상태와 사용자 수신·인증·비밀번호 변경을 확인했다. 전용 검수 프로젝트를 중지하고 dev 모드로 복귀했다. 인증 화면·계정 보안 안내·인증 이메일에 한국어를 적용했다. 운영 관리자 MFA·일회용 복구 코드·운영자 분실 복구는 임시 관리자 계정으로 검증했고, 본인 운영 관리자 등록 확인은 별도다. 다음 구현 단위는 **DEV 테스트 사용자 초기화**다. 공통 소셜 콜백과 SHNEA 브랜드 화면을 유지한다. 현재 `PLATFORM_MODE=dev`, 사용자 지정 검수 주소는 https://platform.shnea.kr 이다. 상세 결과·데이터 정리는 STATUS.md, 관리자 등록·분실 복구는 ADMIN_SECURITY.md를 확인한다. 관리자 위임, 파일·6채널 일반 알림·웹훅·에디터는 계속 미완료다.

작업은 작은 단위로 끝내고 변경에 필요한 검증만 수행한다. 완료 시 STATUS.md를 갱신하고 한글 커밋·푸시한다. 플랫폼과 무관한 브라우저 탭이나 검색 내역은 조회하지 않는다.

새 대화에서 사용할 요청 예:

> AGENTS.md, REQUIREMENTS.md, docs/STATUS.md, docs/HANDOFF.md를 읽고 코드와 원격 상태를 확인해 줘. 본인 운영 관리자 MFA 등록 확인 상태를 먼저 확인하고, 다음 구현인 DEV 테스트 사용자 초기화를 작은 단위로 이어서 진행해 줘. 외부 이메일 검수와 개발 모의 검증을 구분하고, 완료한 단위마다 필요한 검증과 한글 커밋·푸시를 해 줘.

공통 소셜 키·콜백은 [설정 안내](SOCIAL_LOGIN.md), NCP 연결 검사와 미완료 범위는 [알림 설정](NOTIFICATION_SETUP.md)을 따른다. 실제 키·테스트 수신처는 `.env.dev`·`.env.prod` 안에서 암호화한다. Git에는 암호문만 전달하고 복호화 키는 별도 보관한다.
