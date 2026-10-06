# SHNEA Platform · AI 연결 지침

기준: 2026-10-06 · API `/api/v1` · 에디터 `0.1.0-alpha.12` / 문서 version 4.
이 파일은 AI용 진입점이다. 필요한 항목의 URL만 읽는다. 플랫폼 저장소·관리자 로그인 없이 자료를 조회할 수 있다. 실제 API 호출에는 아래 인증이 필요하다.

## 입력받을 값

`사용 기능`, `호스트 기술/주소`, `프로젝트 ID`, `환경 ID(DEV/PROD)`, `서버 키 환경변수명`, 로그인 사용 시 `issuer/콜백`.
기본 주소: `https://platform.shnea.kr`. 키 원문은 문서·프롬프트에 넣지 않고 호스트 서버에 주입한다.
값은 관리자 **프로젝트 → 서비스 연결**에서 환경을 선택해 `.env.dev` / `.env.prod` 형식으로 복사한다. 프로젝트·환경 ID는 코드명이 아닌 UUID다. `ADMIN_OIDC_*`는 호스트의 관리자 판별 방식이 `iss`+`sub`일 때만 사용한다.

## 기능 → 읽을 자료

| 작업 | 문서·명세·예제 URL |
| --- | --- |
| 최신 연결 지침 | https://platform.shnea.kr/integrations/SERVICE_INTEGRATION.md |
| 로그인 연결 | https://platform.shnea.kr/integrations/auth.md |
| 환경 확인·DEV Mock API | https://platform.shnea.kr/integrations/project.openapi.json |
| 파일 업로드·재개·보기·공유·삭제 API | https://platform.shnea.kr/integrations/file.openapi.json |
| 실행 가능한 서버 파일 클라이언트 (Python 3.11+) | https://platform.shnea.kr/examples/file-client.py |
| 외부 Job · pull 워커 연결 | https://platform.shnea.kr/integrations/jobs.md |
| Job API · Python 워커 | https://platform.shnea.kr/integrations/jobs.openapi.json · https://platform.shnea.kr/examples/jobs-client.py |
| 공통 로그 · 비동기 전송 | https://platform.shnea.kr/integrations/logs.md |
| 로그 API · Python 전송기 | https://platform.shnea.kr/integrations/logs.openapi.json · https://platform.shnea.kr/examples/logs-client.py |
| 공통 AI · 단일/배치 임베딩·Raya·n8n 전체 기준 | https://platform.shnea.kr/integrations/ai.md |
| AI API · Python 서버 클라이언트 | https://platform.shnea.kr/integrations/ai.openapi.json · https://platform.shnea.kr/examples/ai-client.py |
| n8n 내부 공통 임베딩 수동 검수 예제 | https://platform.shnea.kr/integrations/n8n-embeddings.sample.json |
| React·Vue·JS·JSP 에디터/뷰어·첨부 연결 | https://platform.shnea.kr/integrations/editor.md |
| 에디터 설치 패키지 | https://platform.shnea.kr/integrations/shnea-editor-0.1.0-alpha.12.tgz |
| 패키지 SHA-256 | https://platform.shnea.kr/integrations/checksums.json |
| 브라우저 실행 예제 | https://platform.shnea.kr/examples/editor/ |

표의 자료는 **인증 없이 HTTP GET**으로 받는다. 링크를 열 수 없다면 그 사실과 필요한 URL을 알리고, 계약을 추측하지 않는다. 일반 JS/JSP는 받은 tgz를 풀어 `package/dist/browser` 전체를 정적 자산으로 사용한다. React/Vue는 내려받은 tgz를 `npm install ./shnea-editor-0.1.0-alpha.12.tgz`로 설치한다. 공개 npm 레지스트리 발행은 아니다.

**에디터 설치·업데이트 시 필수:** https://platform.shnea.kr/integrations/editor.md 의 ‘파일 보기 URL 연결 규칙’을 적용한다. `thumbnailUrl`·`previewUrl`·`originalUrl`을 같은 `/content` 주소로 덮어쓰지 않는다. 패키지 업데이트는 호스트의 파일 조회·중계 코드를 수정하지 않는다. 호스트 수정·재배포 후 실제 요청 순서까지 검증한다.

이미지 기본 연결은 `attachments.platformImageOrigin` 설정 + 호스트의 권한 확인·플랫폼 보기 응답 원문 반환이다. 이미지 URL 해석·검증과 PC·모바일 본문 미리보기 표시는 에디터가 처리한다. 자세한 연결 코드는 위 에디터 지침만 읽는다.

## 호출 계약

| 기능 | 인증·전제 |
| --- | --- |
| 환경 확인 | `GET /api/v1/integration/context` + `X-Platform-Key`, `integration:read`. 응답의 프로젝트·환경을 입력값과 대조 |
| 이용자 로그인 | 환경 issuer의 OIDC discovery, client `app`, Code + PKCE S256. 서버 키/관리자 계정 사용 금지 |
| 파일 | 호스트 서버에서 `X-Platform-Key`. `files:read/write/delete/share` 중 필요한 권한만 발급. 프로젝트 파일 사용 켜기 필요 |
| 에디터 | 패키지 자체는 인증 불필요. 본문 JSON 저장·사용자 권한·첨부 전송은 호스트 책임 |
| Job | 호스트 서버 키 `jobs:write/read/work`. 플랫폼이 큐 관리, 프로젝트 워커가 실행. 업무는 job.id로 멱등 처리 |
| 로그 | 호스트 서버 키 `logs:write/read`. 비동기·제한된 전송, 민감값 제외. 7일 보존·환경별 한도 |
| AI | 호스트 서버 키 `ai:read/route/embed/execute/jobs:read/cancel/usage/index:write/index:read/index:search`. Raya·임베딩, v14 블로그 태그·요약·댓글·실험글 초안 포함 9종 AI·RAG 실행·usage, v13 PostgreSQL 문서 색인 전체 교체·검색. Qdrant 예제와 별도 |

플랫폼 로그인 기능을 쓰지 않거나 다른 OIDC로 로그인하는 프로젝트도 서버 키로 파일·Job·로그·AI 서비스를 독립 이용한다. 최종 이용자의 인증·인가·익명 이용은 호스트 서버가 판단하고 플랫폼 Keycloak으로 이전하지 않는다. 관리자 등록·환경 설정·키 발급에는 관리자 로그인이 필요하다. 기존 키는 AI 권한을 자동으로 받지 않는다.

## 구현 규칙

1. 호스트 서버가 사용자 권한/파일 소유 관계를 확인한다. 서버 키는 브라우저·앱·URL·로그에 넣지 않는다. 관리자/내부 API·플랫폼 DB를 사용하지 않는다.
2. 파일은 기본 공개. 보호할 파일은 `PRIVATE`. 본문에는 `fileId` 저장, 임시 보기 URL은 필요할 때 조회한다. 업로드 세션 재개는 생성한 키로 한다.
3. 외부 도메인의 기본 뷰어 iframe·직접 HLS fetch는 현재 임베드/CORS 제한이 있다. 새 탭 뷰어를 쓰거나 호스트 중계·플레이어를 별도 구현한다. 무조건 허용된다고 가정하지 않는다.
4. 보존 코드 `default`는 영구가 아니다. 장기 첨부의 정책을 명시한다. 본문 제거와 원본 삭제를 분리한다.
5. 오류는 HTTP 상태 + `code`로 판단하고 `detail`·응답 `X-Request-ID`를 처리한다. 통신 실패 후 변경 요청을 무조건 재실행하지 않는다.
6. Job 점유 만료 시 실행·보고를 중지한다. 로그 장애가 사용자 요청을 막지 않게 한다. 일반 알림 발송·외부 웹훅 API는 아직 미제공.
7. AI는 원문·벡터를 플랫폼에 저장하지 않는다. 검색/인덱싱은 같은 임베딩 모델·차원을 사용하고 기존 다른 모델 벡터를 섞지 않는다. Raya는 이미지 분석·답변 생성이 아니다. n8n 수동 예제를 외부 실행 API로 안내하지 않는다.

## 실행 순서

필요 자료 GET → 입력/환경 확인 → 선택 기능 구현 → DEV 검수.
완료 보고: 변경 내용, 실제 실행한 검사, 남은 설정/미검증 항목. 문서 조회 성공을 실제 서비스 연결 성공으로 보고하지 않는다.
