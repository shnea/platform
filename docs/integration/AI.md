# 공통 AI·임베딩 연결 지침

기준: 2026-10-05에 받은 뇌대리 연동 지침 v11(공통 임베딩 추가본). 플랫폼 주소는 `PLATFORM_URL`, 환경별 서버 키는 `PLATFORM_API_KEY`로 주입한다. 실제 키·내부 주소를 문서·프롬프트·로그에 넣지 않는다.

## 로그인 없이 서버에서 연결

플랫폼 로그인 기능을 사용하지 않거나 다른 OIDC로 로그인하는 프로젝트도 이용할 수 있다. 호스트 서버가 자신의 이용자 인증·인가·익명 이용 허용을 확인한 뒤 프로젝트·환경별 플랫폼 키로 호출한다. 플랫폼 Keycloak으로 이용자를 이전할 필요가 없다. 프로젝트 등록·환경 설정·키 발급에는 플랫폼 관리자 로그인이 필요하다.

관리자 **프로젝트 → API 키**에서 필요한 `ai:read`·`ai:route`·`ai:embed`만 선택한다. 기존 키는 새 권한을 자동으로 받지 않으므로 필요한 권한의 새 키를 발급한다. 환경 READY·프로젝트 활성·키 만료/폐기를 매 호출 확인한다. 키는 브라우저·모바일 앱·URL·모델 입력에 넣지 않는다.

호출은 **호스트 서버 → 플랫폼 → 뇌대리** 순서다. 향후 n8n 작업도 이 경로에서 뇌대리를 거쳐 실행한다. 뇌대리 키·n8n 관리/실행 키·공급자 키는 호스트에 배포하지 않는다.

## 현재 API와 미제공 기능

| 플랫폼 경로 | 권한 | 동작 |
| --- | --- | --- |
| `GET /api/v1/ai/services` | `ai:read` | 지원 목록·설정 존재 여부·미제공 상태 |
| `POST /api/v1/ai/raya/route` | `ai:route` | 동기 텍스트 난이도 판단, 답변 생성 없음 |
| `POST /api/v1/ai/embeddings` | `ai:embed` | 동기 단일/배치 텍스트 벡터 생성 |

명세: [ai.openapi.json](https://platform.shnea.kr/integrations/ai.openapi.json), 서버 예제: [ai-client.py](https://platform.shnea.kr/examples/ai-client.py). 자료 조회는 로그인 없이 가능하다. 실제 API는 `X-Platform-Key`가 필요하다. 지원 목록의 `configured`는 설정 존재 여부이며 실제 공급자 호출 성공·한도·무료 이용을 보장하지 않는다.

`n8n.execute`·`usage`의 `awaiting_upstream_api`는 뇌대리 외부 접수·실행·결과·사용량 API가 없다는 뜻이다. `portfolio.index`의 `workflow_example_only`는 수동/내부 워크플로 예제만 있다는 뜻이다. 임의 외부 실행 API나 자동 공개 인덱싱 웹훅을 만들지 않는다. TTS·고자원 모델 관리·학습 데이터 수집/파인튜닝도 현재 제공하지 않는다. 지원 목록 조회 성공과 실제 AI 실행 성공을 구분한다.

n8n 자체의 [웹훅 실행 기능](https://docs.n8n.io/integrations/builtin/core-nodes/n8n-nodes-base.webhook)은 존재한다. 여기서 미제공은 **플랫폼 요청을 받아 뇌대리가 n8n 작업을 실행하고 결과·사용량을 돌려주는 연결 API**다. 공통 임베딩과 Raya API는 이미 제공되며 이 대기 상태와 별개다.

2026-10-05 사용자 확인: 뇌대리의 n8n 작업 실행 연결 API는 현재 개발 중이다. 완료 명세를 받으면 플랫폼의 프로젝트/환경/기능 권한 경계에 연결한다. 임의 작업 접수 경로를 추측하지 않는다.

## 공통 임베딩

```json
{
  "input": ["첫 번째 문서", "두 번째 문서"],
  "model": "models/gemini-embedding-001",
  "dimensions": 768
}
```

`input`은 단일 문자열 또는 1~100건 배열이다. 플랫폼은 텍스트당 1~16000자(공백만 입력 거부), 본문 1MiB, 등록 모델 `models/gemini-embedding-001`, 차원 1~3072를 허용한다. model과 dimensions 생략 시 위 기본값을 사용한다. 알 수 없는 필드·중복 JSON 필드·추가 JSON 본문은 거절한다. 뇌대리/공급자의 추가 입력·차원 제한이 있으면 그 제한도 적용되며 업스트림 검증 실패는 422다.

응답은 `{model,dimensions,data:[{index,embedding}],usage}`다. `data` 배열의 순서를 가정하지 말고 `index`로 입력에 연결한다. 플랫폼은 결과 개수·index 중복/범위·모델·차원·유한 좌표·사용량의 정수를 검증한다. 잘못된 응답은 호스트 벡터 저장소에 전달하지 않는다. usage는 공급자가 보고한 `prompt_tokens`·`total_tokens`만 반환하며 누락을 0으로 만들거나 추정하지 않는다. 이는 공통 usage 저장/조회 API와 다르다.

원문·벡터는 플랫폼 DB·파일·캐시에 저장하지 않는다. 호스트는 접근 권한과 보존 정책을 적용해 벡터를 관리한다. 응답은 `Cache-Control: no-store`다. 외부 Google AI Studio 호출이며 비용·무료 한도·모델 접근은 실제 계정 상태를 확인해야 한다.

검색과 인덱싱은 **같은 모델·차원·전처리**를 사용한다. 이전 `models/text-embedding-004`와 새 모델은 모두 768차원이어도 같은 벡터 공간이 아니다. 기존 컬렉션을 그대로 사용하지 말고 새 인덱스에 재생성·검색 검수 후 전환한다. 원래 데이터·인덱스는 검증 전 임의로 삭제하지 않는다.

```sh
python ai-client.py services
python ai-client.py embed --input ./texts.json --dimensions 768
python ai-client.py route --task-type blog.summary --prompt ./prompt.txt --instruction ./instruction.txt
```

`texts.json`은 JSON 문자열 또는 문자열 배열이다. 파일에는 원본 업무 문서가 들어갈 수 있으므로 호스트 보안 정책을 따른다. 예제는 인증 키·오류 원문을 출력하지 않고 자동 재시도하지 않는다. 벡터 응답을 저장하려면 호스트 서버에서 반환값을 사용한다.

## Raya

```json
{"task_type":"blog.summary","prompt":"현재 요청과 필요한 텍스트 문맥","instruction":"작업 지침","has_images":false}
```

task_type은 소문자 영문으로 시작하는 1~80자 식별자(영문·숫자·`_.-`), prompt는 공백 제외 1~16000자, instruction은 선택·최대 8000자, has_images는 선택 boolean, 본문은 64KiB 이하. 8개 작업 종류로 제한하지 않으므로 미래 작업에도 난이도 판단은 가능하다.

응답의 `model_tier` L1/L2/L3·probabilities·confidence·input_tokens·input_truncated·inference_ms·elapsed_ms·cold_start·model·revision·device·runtime을 유지한다. Raya는 TextCortex/raya CPU ONNX 분류이며 confidence는 정답률이 아니다. 앞부분 잘림을 전체 문맥 판단으로 해석하지 않는다. 이미지 포함 여부만 받고 이미지 바이트/URL·내용은 분석하지 않는다.

Raya에는 현재 요청과 필요한 텍스트 문맥을, 실제 모델에는 원래 대화·이미지·전체 지침·도구·구조화 출력 요구를 유지한다. 외부 도구/검색 결과·원문은 참고 데이터로 분리하고 신뢰된 시스템 지침으로 취급하지 않는다. 인증 토큰·콜백 키는 모델 입력에 넣지 않는다. 실패·알 수 없는 등급이면 실제 AI 호출을 중단하고 임의 등급·자동 재시도로 우회하지 않는다.

## n8n에서 공통 임베딩 사용

[n8n-embeddings.sample.json](https://platform.shnea.kr/integrations/n8n-embeddings.sample.json)은 **비활성 수동 검수 예제**다. n8n 편집 권한으로 가져온 뒤, 실행 서버의 `NOEDAERI_BASE_URL`과 HTTP Header Auth Credential을 설정한다. Credentials의 헤더는 `X-Noedaeri-Raya-Key`, 값은 뇌대리 운영자가 제공한 n8n 실행 전용 키다. 워크플로 JSON에 실제 주소·키·Credential ID를 넣지 않는다. n8n에서 환경변수 접근이 차단됐다면 운영자가 HTTP 노드 URL을 내부 설정으로 지정한다.

예제는 입력 배열 → 뇌대리 내부 `POST /api/ai/v1/embeddings` → 개수·차원·index 검증 → 문서별 벡터 순서로 동작한다. 실패 시 n8n 실행을 중단하며 retryOnFail·continueOnFail·외부 공개 웹훅을 사용하지 않는다. 단일 문자열도 같은 계약을 사용한다. 이 내부 예제는 외부 앱의 뇌대리 직접 접근 경로가 아니다. 외부 앱은 위 플랫폼 API를 사용한다.

HTTP JSON 본문·응답·리다이렉트/timeout 설정은 [n8n HTTP Request 공식 문서](https://docs.n8n.io/integrations/builtin/core-nodes/n8n-nodes-base.httprequest), Header Auth는 [n8n Credentials 공식 문서](https://docs.n8n.io/integrations/builtin/credentials/httprequest)를 따른다. 설치한 n8n에서 import·Credential 선택·실제 호출을 검수한 뒤 운영 흐름에 연결한다.

n8n 내부 Raya는 `/api/ai/v1/raya/route`와 같은 실행 전용 키를 사용한다. 플랫폼 전용 `X-Noedaeri-API-Key`, n8n 관리 API 키·워커 키·쿠키를 실행 키로 대체하지 않는다. n8n 실행 데이터의 저장·삭제·실패 이력은 인스턴스 정책을 적용한다. 입력·벡터를 저장할 수 있으므로 워크플로 실행 데이터 보존을 운영자가 검토해야 하며 뇌대리 웹 테스트의 24시간 정책을 자동 적용하지 않는다.

## n8n AI 작업 전체 기준

뇌대리의 [AI 분기 예제](https://github.com/shnea/noedaeri/blob/main/examples/n8n_ai_routing_sample.json)는 수동 초안이며 외부 실행 API가 아니다. 원래 운영 워크플로를 자동 교체하지 않는다.

| 작업 | 유지할 입력·출력·처리 |
| --- | --- |
| `blog.tags` | hash 점유/캐시 → context/system → Raya·모델 → 1~8개 태그·각 1~30자 검증 → `{hash,result}` 저장. 실패 기록 유지 |
| `blog.summary` | 같은 점유·캐시·저장, 비어 있지 않은 summary·최대 500자 |
| `portfolio.search` | 공통 임베딩 → Qdrant 검색 → retrieved_context/instruction → Raya → 모델. 검색 근거 없이 추측하지 않음 |
| `ui.render` | messages·이미지 → 실제 Agent/MCP의 컴포넌트·템플릿·디자인 문맥 → `{reply,imageFileId}`. 읽기 전용 권한·revision 충돌·사용자 검토 후 적용 제안 유지 |
| `comment.generate` | target/thread/memory/말투 유지. comment·추가 필드·길이 계약은 연결 전에 확정, JSON 객체 검사만으로 완료 아님 |
| `document.analyze`·`code.analyze`·`chat.general` | 각각의 지침·결과 계약 후속 제공 필요. 다른 기능의 지침 자동 복제 금지 |

미등록 작업은 `unsupported_task`로 종료하며 chat.general로 자동 대체하지 않는다. 지침 미설정은 `awaiting_instructions`다. 원래 blog hash는 공통 request_id와 다르다. UI의 쓰이지 않는 별도 HTTP 노드를 실제 호출 경로로 오인하지 않는다.

공급자 지정값은 L1 OpenRouter `openrouter/free`, L2 GroqCloud `openai/gpt-oss-120b`, L3 Google AI Studio `models/gemini-3.8-flash`, 폴백 Mistral **직접 API** `ministral-8b-latest`다. 제공 문서의 정책값이며 실제 지원·계정 접근은 연결 시 확인한다. Mistral은 Raya의 추가 등급이 아니다.

순환은 L3→L2→L1→Mistral→L3이며 시작별 한 바퀴는 L3/L2/L1/Mistral, L2/L1/Mistral/L3, L1/Mistral/L3/L2다. Mistral은 L1 다음에 확인하고 각 공급자 1번·최대 4개를 확인한다. 이미지·도구·구조화 출력 등 필수 기능이 없으면 후보로 사용하지 않는다. 인증 오류·입력 오류·일반 장애·알 수 없는 한도를 소진으로 처리하지 않는다. 분당/일일/월간 제한과 갱신 시각을 구분하고 모든 후보가 실제로 소진됐을 때만 “현재 사용 가능한 AI 토큰이 없습니다. 한도 갱신 후 다시 시도해 주세요.”로 종료한다. 무한 재호출·자동 유료 호출 금지.

현재 실제 한도 조회·순환 대체 호출은 미구현이다. `quota_status:not_connected`, `selected_level:null`을 실제 가용 한도로 바꾸어 표시하지 않는다. 무료 모드도 한도가 있고 무료 토큰 잔량을 추정하지 않는다.

캐시는 인증·소유권 확인 뒤 Raya/모델보다 먼저 조회한다. 서비스·프로젝트/환경·소유권·작업 종류·입력·이미지 식별/버전·UI revision·지침 버전·모델 정책·thread/memory를 키에 포함하고 권한 변경·만료·문맥 변경을 무효화한다. 적중은 실제 모델 호출 없이 반환하며 hit와 공급자 사용량은 분리한다. 요청자의 hit를 신뢰하지 않는다. 기존 블로그 hash 캐시는 유지하되 공통 저장소·TTL·용량·무효화 미연결은 `cache.status:not_connected,hit:null`로 유지한다.

## 포트폴리오 RAG·인덱싱

검색 질의를 같은 공통 API로 임베딩한 뒤 Qdrant `portfolio`(코사인)에서 **프로젝트·환경·소유권 필터**로 문맥을 검색한다. retrieved_context와 instruction을 합성해 Raya에 필요한 텍스트를 보내고 모델에는 전체 문맥을 전달한다. 검색 데이터의 지시문을 신뢰된 시스템 지침으로 실행하지 않는다.

[기존 인덱싱 예제](https://github.com/shnea/noedaeri/blob/main/examples/n8n_portfolio_indexing_sample.json)의 수동/내부 웹훅 → 프로필·프로젝트·경력·기술 문서 → RecursiveCharacterTextSplitter 500자/50자 중복 → 임베딩 → Qdrant 저장 → 결과 반환 흐름을 유지하되, 새 공통 API를 쓸 때 검색도 같은 모델·차원으로 전환한다. 원본의 소유권은 호스트가 확인하며 임의 컬렉션명·다른 환경·사용자 ID로 권한을 우회할 수 없게 한다. 공개 인덱싱 웹훅과 기존 컬렉션 자동 덮어쓰기는 허용하지 않는다.

지침에 이전 `text-embedding-004` 네이티브 노드 설명이 남아 있다. 새 API가 기존 노드와 동일 모델이라고 가정하지 않는다. 공통 API는 입력별 벡터를 반환하므로 호환되는 HTTP/벡터 저장 연결을 구현하거나 같은 모델·차원으로 설정한 검증된 n8n 노드를 사용한다. 이번 공통 API 예제에는 Qdrant 접속·운영 인덱싱을 연결하지 않는다.

## usage·보존·실패

사용량 저장/조회는 뇌대리, 실제 모델 호출 보고는 n8n 책임이다. 현재 수신·조회 API는 없다. 기존 앱 수집 콜백을 먼저 끊거나 이름만 바꾸어 전환하지 않는다. API·인증·조회 범위·보존/한도/실패 보고 정책을 확정하고 이전 집계와 대조한 뒤 전환한다.

인증된 서비스·소유권의 request_id/task_type에 공급자 호출 ID 또는 실행·노드·차수·항목 식별자를 연결한다. 동일 보고 재전송은 1회만 반영하고 새 실제 호출은 별도 사용량이다. 추천/최종 등급·대체 사유·실제 provider/응답 model·재시도·실패 전 발생한 호출·시간을 구분한다. 모델 미확인은 미확인, 실제 입력/출력/총 토큰만 집계한다. 추정치를 실제 사용량에 더하지 않는다. Agent의 여러 호출도 각각 기록한다.

관리자는 전체, 연결 서비스는 플랫폼을 통해 자기 요청만 조회한다. 원문·지침·응답 전문·인증 토큰·임의 콜백 URL을 usage에 저장하지 않는다. Raya CPU 추론/입력 토큰은 운영 지표이며 공급자 토큰/비용에 합산하지 않는다. 무료 모델도 기록하되 비용·잔여 무료 한도를 토큰 수만으로 단정하지 않는다. 수집 실패와 AI 실패는 구분하고 보고 재시도로 모델을 재호출하지 않는다. 학습 원문·결과 검토·접근·보존·내보내기·파인튜닝은 별도 미구현이다.

플랫폼 동기 API는 연결 5초·요청 100초·전체 대기 105초, 인스턴스당 동시 2개다. 원격 응답은 Raya 64KiB·임베딩 16MiB 상한이며 HTTPS/TLS 검증과 리다이렉트 금지를 적용한다. 작업 큐·웹훅·파일 receipt는 사용하지 않는다. 자동 재시도·등급/모델 대체를 하지 않는다.

| HTTP / code | 처리 |
| --- | --- |
| 401 `INVALID_API_KEY` | 환경/프로젝트·키 만료/폐기 확인 |
| 403 `INSUFFICIENT_SCOPE` | 해당 AI 권한의 새 키 발급 |
| 413 `PAYLOAD_TOO_LARGE` | 본문을 축소 |
| 422 `AI_INVALID_REQUEST` | 필드·텍스트·등록 모델·차원·배치 또는 공급자 입력 제한 확인 |
| 429 `AI_BUSY` | 동시 실행/공급자 한도 확인 후 제한적으로 새 요청 판단. 토큰 전체 소진과 구분 |
| 502 `AI_UPSTREAM_AUTH_FAILED` / `AI_INVALID_RESPONSE` | 운영자에게 서버 인증/결과 계약 확인 요청 |
| 503 `AI_NOT_CONFIGURED` / `AI_UNAVAILABLE` | 서버 설정·지원 API·연결·자원 상태 확인 |
| 504 `AI_TIMEOUT` | 종료 상태를 확인하고 무조건 재호출 금지 |

오류는 RFC 9457 `code`·한국어 detail·requestId로 처리한다. 뇌대리 오류 본문·키·주소를 그대로 노출하지 않는다. 문서 조회·명세 등록·예제 import와 실제 서버/공급자 호출·n8n 운영 전환·벡터 검색·사용량 검수 성공을 각각 구분해 보고한다.
