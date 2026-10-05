# 뇌대리 AI 어댑터

플랫폼 외부 API·n8n 전체 지침은 [공개 AI 연결 지침](integration/AI.md), 확정 범위는 [F-20](REQUIREMENTS.md#공통-ai임베딩뇌대리n8n--f-20)을 따른다. 2026-10-05 AI 실행·조회·취소·usage·공통 RAG 추가본 v12을 기준으로 구현했다.

프로젝트 서비스에서 환경별 서버 키·`ai:read`/`ai:route`/`ai:embed`/`ai:execute`/`ai:jobs:read`/`ai:cancel`/`ai:usage`를 검사한 뒤 HTTPS로 뇌대리의 동기·비동기 AI API에 연결한다. 로그인 서비스를 사용하는지 또는 호스트가 어느 OIDC를 사용하는지와 독립적이다. 기존 키·기존 워크플로·공급자 설정·벡터 컬렉션은 변경하지 않는다. 별도 AI 서비스·DB·큐를 만들지 않고 기존 프로젝트 인증 경계를 사용한다.

운영 Compose의 project-service에도 기존 암호화 `NOEDAERI_BASE_URL`·`NOEDAERI_PLATFORM_API_KEY`를 주입한다. 파일 변환과 같은 서버 키이며 웹훅 비밀·n8n 실행 키·관리 키를 요구하지 않는다. 설정 존재는 실제 AI 공급자 연결 성공과 구분한다. 새 비밀값을 문서·이미지·Git에 추가하지 않는다.

`AiGateway`는 본문/응답 크기·필드·배치·문자수·모델·차원·index·유한 좌표를 검사하고 원문·벡터·사용량을 DB나 로그에 저장하지 않는다. 임베딩 usage는 응답의 실제 보고값이며 중앙 집계가 아니다. 동기 API는 파일 웹훅/inbox·generation·receipt와 분리한다. AiJobs는 v12의 접수·조회·취소·usage를 현재 환경으로 고정한다. V13 원장은 request_id·내용 해시·원격 ID만 기록하며 최초 요청만 POST한다. 재전송은 GET으로 확인하고 불명확 접수는 최근100건 목록에서 복구한다. 타 환경의 조회/취소·컬렉션 위조·변경 내용 재사용·만료 결과를 차단한다. usage는 upstream_reported_unverified, 취소는 실행 종료 미확인으로 명시한다.

관리자 **개발자 센터 → AI·임베딩**에서 계약·명세·Python 서버 예제·n8n 수동 임베딩·AI 작업 호출·네이티브 공통 인덱싱 예제와 지원 상태를 확인한다. 공개 배포 자료는 build-integration.mjs의 허용 목록으로 생성하며 관리자/내부 API·운영 설정을 공개 명세에 포함하지 않는다. Nginx는 AI 경로의 2MiB 프록시 입력(컨트롤러별 64KiB/1MiB/2MiB 제한)·120초 응답 대기를 적용한다.

검증은 프로젝트 서비스 JUnit의 모의 HTTPS 정책/HTTP 계약·실제 Spring 보안 필터·격리 DB의 권한 발급/차단과 기존 회귀, 관리자 TypeScript/Vite 빌드, 공개 명세 참조·n8n 예제 검증을 사용한다. 실제 공급자는 `AI_LIVE_CHECK=true`일 때 `AiLiveTest`로 생성한 짧은 텍스트만 보내며 두 서버 설정만 전달한다. 운영 DB·사용자 원본·플랫폼 운영 복호화 키를 검사 컨테이너에 제공하지 않는다. 실제 실행 결과·미검증·다음 작업은 [상태 문서](STATUS.md)에 기록한다.
