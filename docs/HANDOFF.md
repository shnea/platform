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

저장소 루트에서 실행한다. macOS 셸·Windows PowerShell 공통 명령이다.

```sh
docker run --rm --mount "type=bind,source=${PWD},target=/workspace" -w /workspace python:3.13-alpine python scripts/init-env.py
docker compose -f compose.yml -f compose.dev.yml config --quiet
docker compose -f compose.yml -f compose.dev.yml up -d --build --wait --wait-timeout 300
docker compose -f compose.yml -f compose.dev.yml --profile setup run --rm --build identity-setup
docker compose -f compose.yml -f compose.dev.yml ps
```

기존 `.env`가 있으면 생성 명령을 생략한다. 기존 환경에 빠진 설정만 추가할 때는 생성 명령 끝에 `--upgrade`를 붙인다. `.env`를 새로 생성해도 이미 존재하는 DB의 비밀번호는 바뀌지 않는다.

관리자 화면은 http://localhost:30140/ 이다. ID는 `admin`, 비밀번호는 새 PC의 `.env`에 있는 `PLATFORM_ADMIN_PASSWORD`를 사용한다. 최초 설정 후 프로젝트·환경을 생성한다. 지금까지 만든 테스트 프로젝트는 기존 PC의 DB에만 있으므로 새 PC에는 자동으로 나타나지 않는다.

자체 이미지는 아직 레지스트리에 게시하지 않았다. 새 PC에서도 개발 Compose로 직접 빌드한다. 첫 빌드에는 기반 이미지·의존성 다운로드가 필요하다. Windows·Linux amd64 실기동은 아직 검증 전이며, 기존 검증은 macOS의 Linux arm64 Docker에서 수행했다.

## Git에 들어 있는 것과 별도 항목

- 코드, Gradle 설정, npm 잠금 파일, Dockerfile, Compose, `.env.example`, 초기화·검사 스크립트, 요구사항·상태·디자인 문서는 저장소에 포함된다.
- 실제 `.env`, 비밀번호·인증 정보, Docker DB 볼륨, 빌드 캐시·이미지, 로컬 화면 캡처는 Git에 포함하지 않는다. 새 개발 DB로 계속 작업할 수 있다. 기존 데이터까지 이어야 한다면 DB 백업과 그 데이터에 맞는 비밀 설정을 별도 보안 경로로 옮겨야 한다. 이번 인계에서는 데이터 백업·이동을 수행하지 않았다.
- 개인 Codex 스킬과 브라우저 도구는 PC별 설치 항목이다. `/private/tmp`의 이전 검사용 임시 도구나 기존 브라우저 세션에 의존하지 않는다. 새 PC에 설치된 도구를 확인해 사용한다.

## 다음 작업

우선 [작업 규칙](../AGENTS.md), [핵심 요구사항](../REQUIREMENTS.md), [현재 상태](STATUS.md)를 읽는다.

다음 구현 단위는 **환경별 사용자 조회·상태 변경·세션 관리**다. 공통 콜백·공통 환경변수 설정은 구현했다. 사용자가 인증 정보를 등록하고 실제 검수를 요청했으며, 현재 결과·운영 모드 임시 전환 상태는 STATUS.md를 먼저 확인한다. 공개 접속 주소는 https://platform.shnea.kr 이다. NCP 이메일 발송·실제 계정 복구, 개발 이메일 모의 수신함도 미완료다. 파일·6채널 알림·웹훅·에디터는 첫 출시 범위에 그대로 남아 있다.

작업은 작은 단위로 끝내고 변경에 필요한 검증만 수행한다. 완료 시 STATUS.md를 갱신하고 한글 커밋·푸시한다. 플랫폼과 무관한 브라우저 탭이나 검색 내역은 조회하지 않는다.

새 대화에서 사용할 요청 예:

> AGENTS.md, REQUIREMENTS.md, docs/STATUS.md, docs/HANDOFF.md를 읽고 현재 코드와 원격 상태를 확인해 줘. 다음 작업인 환경별 사용자 조회·상태 변경·세션 관리를 작은 단위로 이어서 구현해 줘. 실제 소셜 로그인·이메일 발송 검수의 최신 상태를 확인하고, 완료한 단위마다 필요한 검증과 한글 커밋·푸시를 해 줘.

공통 소셜 키·콜백은 [설정 안내](SOCIAL_LOGIN.md), NCP 연결 검사와 미완료 범위는 [알림 설정](NOTIFICATION_SETUP.md)을 따른다. 실제 키·테스트 수신처는 `.env`에만 있으며 Git으로 이동하지 않는다.
