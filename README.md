# 공통 서비스 플랫폼

여러 프로젝트가 로그인, 파일, 알림, 블록 에디터·뷰어를 선택해 사용하고 한 관리자 화면에서 운영하는 플랫폼이다. 기존 서비스 코드를 재사용하지 않고 새로 구현하며, 앞으로 추가되는 프로젝트에 적용한다.

프로젝트·환경 관리, 중지·재개, Keycloak 브라우저 관리자 로그인, 서버 API 키, 감사 이력과 개발 소셜 Mock 로그인 API를 구현했다. 환경별 회원 조회·상태·세션과 가입·복구 정책을 관리할 수 있다. 인증 이메일의 NCP 발송 경로와 개발용 모의 수신함을 연결했고, 모의 메일로 실제 이메일 인증·비밀번호 재설정 흐름을 검증했다. 이번 NCP 어댑터의 실제 외부 수신 검수는 별도다. 관리자 화면은 어두운 테마가 기본이며 밝은 테마로 전환할 수 있다. 구글·카카오·네이버 설정·공통 콜백과 세 제공자의 실제 계정 연결을 검수했다. 파일은 서버 API 기반 분할·재개 업로드, 원본 다운로드·공개 범위·삭제까지 구현했으며 관리자 파일 메뉴의 멀티·드래그 업로드, 일시정지·재개와 파일 관리도 제공한다. 저장 목록의 썸네일·문서·영상 보기와 URL, 보존 정책·자동 정리도 제공한다. 이용자 직접 업로드·비밀번호 공유와 HLS 화질 변환은 후속 단계다. 일반 알림 업무 API·에디터도 미완료다. [프로젝트·인증 API](docs/PROJECT_API.md), [파일 서비스](docs/FILES.md), [인증 이메일 안내](docs/IDENTITY_EMAIL.md)를 참고한다.

## 개발 실행

관리자 화면의 DEV 환경에서는 **개발 로그인 테스트**로 세 소셜 제공자의 성공·취소·동의 거부·장애를 재현할 수 있다. 외부 소셜 앱 자격증명 없이 실행하며 이용자 토큰은 화면에 노출하지 않는다.

Docker Desktop(또는 Linux Docker Engine)과 Compose가 필요하다. Java·Gradle·Node 빌드는 이미지 안에서 실행한다. 저장소 루트에서 실행한다. 아래 명령은 macOS 셸과 Windows PowerShell에서 사용할 수 있다.

```sh
docker run --rm --mount "type=bind,source=${PWD},target=/workspace" -w /workspace python:3.13-alpine python scripts/init-env.py
docker compose -f compose.yml -f compose.dev.yml config --quiet
docker compose -f compose.yml -f compose.dev.yml up -d --build --wait --wait-timeout 300
docker compose -f compose.yml -f compose.dev.yml --profile setup run --rm --build identity-setup
```

첫 명령은 서로 다른 무작위 개발 비밀번호로 `.env`를 만든다. 기존 파일이 있으면 덮어쓰지 않고 종료하므로, 재실행 때는 첫 명령을 생략한다. `.env`는 커밋하지 않는다.

이전 실행 기반에서 업데이트했다면 첫 명령의 끝에 `--upgrade`를 붙여 새 환경변수만 추가한 뒤 나머지 명령을 실행한다. 기존 DB·관리자 비밀번호는 보존한다. `identity-setup`은 플랫폼 관리자 realm과 제한된 서비스 계정을 설정하며 기존 사용자 비밀번호를 재설정하지 않는다.

| 주소 | 현재 동작 |
|---|---|
| http://localhost:30140/ | 플랫폼 관리자 화면 |
| http://localhost:30140/healthz | Nginx 상태 |
| http://localhost:30140/api/projects/health | 프로젝트 서비스와 DB 연결 상태 |
| http://localhost:30140/api/files/health | 파일 서비스와 DB 연결 상태 |
| http://localhost:30140/api/notifications/health | 알림 서비스와 DB 연결 상태 |
| http://localhost:30140/auth/admin/ | Keycloak 기본 관리 화면 |

Keycloak의 최초 관리자 ID와 비밀번호는 로컬 `.env`의 `KEYCLOAK_ADMIN`, `KEYCLOAK_ADMIN_PASSWORD`를 확인한다. 플랫폼 관리자 화면은 별도 계정 `admin`과 `.env`의 `PLATFORM_ADMIN_PASSWORD`로 로그인한다. 기본 바인딩은 로컬 PC 전용이다. 관리자 주소는 `PLATFORM_WEB_URL`의 정확한 origin과 일치해야 한다. 주소 변경 후 `identity-setup`을 다시 실행한다. 운영은 HTTPS 주소가 필요하다.

운영 도메인은 `platform.shnea.kr`이다. TLS 배포 시 `PLATFORM_MODE=prod`, `PLATFORM_WEB_URL=https://platform.shnea.kr`, `KEYCLOAK_PUBLIC_URL=https://platform.shnea.kr/auth`로 설정하고 `identity-setup`을 실행한다. 관리자 로그인·로그아웃 콜백은 `https://platform.shnea.kr/`로 등록된다. 서비스 Nginx 진입 포트는 `30140`을 유지한다. DNS·TLS 종료 및 신뢰할 프록시의 전달 헤더 설정은 운영 배포 단계에서 적용·검증한다. 현재 로컬 개발 설정은 변경하지 않는다.

## 검증과 중지

```sh
docker compose -f compose.yml -f compose.dev.yml --profile test run --rm smoke
docker compose -f compose.yml -f compose.dev.yml --profile test run --rm db-check
docker compose -f compose.yml -f compose.dev.yml --profile test run --rm project-check
docker compose -f compose.yml -f compose.dev.yml --profile test run --rm project-check python /checks/check-lifecycle.py
docker compose -f compose.yml -f compose.dev.yml --profile test run --rm project-check python /checks/check-mock.py
docker compose -f compose.yml -f compose.dev.yml --profile test run --rm project-check python /checks/check-members.py
docker compose -f compose.yml -f compose.dev.yml ps
docker compose -f compose.yml -f compose.dev.yml logs --tail 80 project-service
docker compose -f compose.yml -f compose.dev.yml down
```

검증 명령은 실행 중인 스택을 대상으로 한다. `down`은 DB 볼륨을 보존한다. DB 비밀번호와 초기화 SQL은 빈 볼륨의 첫 시작에만 적용되므로, 기존 DB의 비밀번호는 `.env` 변경만으로 바뀌지 않는다.

서비스 하나만 수정했다면 `docker compose -f compose.yml -f compose.dev.yml up -d --build --no-deps project-service`로 다시 빌드한다. CPU·메모리 설정은 `.env`에서 바꾸고 `up -d --no-build`로 컨테이너를 재생성한다.

운영용 `compose.yml`에는 빌드 경로나 소스 마운트가 없다. 운영 배포 전 이미지 게시, Linux 대상 아키텍처, TLS와 도메인, 관리자 보호, 백업·복원 검증을 완료해야 한다. 현재 개발 이미지는 운영 출시본이 아니다. 구성과 경계는 [실행 기반 문서](docs/ARCHITECTURE.md)를 참고한다.

관리자 화면 수정 후에는 `docker compose -f compose.yml -f compose.dev.yml up -d --build --no-deps admin-web`을 실행한다. 이미지 빌드 과정에서 `npm ci`, TypeScript 검사와 Vite 빌드를 실행한다. 로그인·설정·모바일 수동 검수 순서는 [관리자 화면 안내](docs/ADMIN_WEB.md)에 있다.

NPM·NAS 뒤에서 접속 기기의 실제 IP를 표시하려면 [역방향 프록시 안내](docs/REVERSE_PROXY.md)의 헤더 신뢰·포트 접근 제한을 먼저 확인한다.

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
- 이미지: `register.shnea.kr/platform-이미지명:태그`.
- 에디터: 첫 출시부터 React·Vue 패키지 및 JSP·일반 JS용 자산 제공. 본문은 각 서비스가 저장.
- Markdown 문서 붙여넣기를 블록·서식으로 변환하고 에디터·뷰어에서 올바르게 렌더링.
- 파일: 최대 5GB, 멀티·드래그·분할·재개 업로드, 기본 공개와 별도 비공개/비밀번호 공유.
- 보존: 고정 `default`·`tmp`·`영구`와 사용자 등록 코드. 코드 기간 변경은 기존 파일에도 적용.
- 알림: 이메일·앱 내·웹 푸시·SMS·카카오·Telegram. 이메일·SMS는 NCP.
- Git: https://git.shnea.kr/shnea/platform.git

## 변경 기록

완료한 작업 단위마다 검증하고 한글 커밋 메시지로 푸시한다. 작업을 넘기거나 중단할 때 현재 상태와 다음 작업을 함께 갱신한다. 기능별 상세 결정은 요구사항 문서를 기준으로 한다.
