# 공통 AI·임베딩 연결 지침

기준: 2026-10-06에 받은 뇌대리 연동 지침 v13(공통 PostgreSQL 색인·검색과 `replace_all` 포함). 플랫폼 주소는 `PLATFORM_URL`, 환경별 서버 키는 `PLATFORM_API_KEY`로 주입한다. 실제 키·내부 주소를 문서·프롬프트·로그에 넣지 않는다.

## 로그인 없이 서버에서 연결

플랫폼 로그인 기능을 사용하지 않거나 다른 OIDC로 로그인하는 프로젝트도 이용할 수 있다. 호스트 서버가 자신의 이용자 인증·인가·익명 이용 허용을 확인한 뒤 프로젝트·환경별 플랫폼 키로 호출한다. 플랫폼 Keycloak으로 이용자를 이전할 필요가 없다. 프로젝트 등록·환경 설정·키 발급에는 플랫폼 관리자 로그인이 필요하다.

관리자 **프로젝트 → API 키**에서 필요한 `ai:read`·`ai:route`·`ai:embed`·`ai:execute`·`ai:jobs:read`·`ai:cancel`·`ai:usage`·`ai:index:write`·`ai:index:read`·`ai:index:search` 중 필요한 권한만 선택한다. 기존 키는 새 권한을 자동으로 받지 않으므로 필요한 권한의 새 키를 발급한다. 환경 READY·프로젝트 활성·키 만료/폐기를 매 호출 확인한다. 키는 브라우저·모바일 앱·URL·모델 입력에 넣지 않는다.

AI 작업은 **호스트 서버 → 플랫폼 → 뇌대리 → n8n** 순서로 실행한다. 임베딩·Raya 동기 호출도 플랫폼을 거쳐 뇌대리에 연결한다. 뇌대리 키·n8n 관리/실행 키·공급자 키는 호스트에 배포하지 않는다.

## 현재 API와 권한

| 플랫폼 경로 | 권한 | 동작 |
| --- | --- | --- |
| `GET /api/v1/ai/services` | `ai:read` | 지원 목록·설정 존재 여부·미제공 상태 |
| `POST /api/v1/ai/raya/route` | `ai:route` | 동기 텍스트 난이도 판단, 답변 생성 없음 |
| `POST /api/v1/ai/embeddings` | `ai:embed` | 동기 단일/배치 텍스트 벡터 생성 |
| `POST /api/v1/ai/jobs` | `ai:execute` | 8종 AI·RAG 작업 동기/비동기 접수 |
| `GET /api/v1/ai/jobs` | `ai:jobs:read` | 현재 환경의 최근 작업 목록·status/limit 필터 |
| `GET /api/v1/ai/jobs/{id}` | `ai:jobs:read` | 현재 환경의 상태·결과 |
| `POST /api/v1/ai/jobs/{id}/cancel` | `ai:cancel` | 소유 범위 확인 뒤 취소 요청 |
| `GET /api/v1/ai/usage` | `ai:usage` | 현재 환경의 공급자·모델·작업별 집계/상세 |
| `POST /api/v1/ai/indexing` | `ai:index:write` | 문서 추가·전체 교체·삭제 접수 |
| `GET /api/v1/ai/indexing`, `GET /api/v1/ai/indexing/{id}` | `ai:index:read` | 색인 작업 목록·상태·결과 요약 |
| `POST /api/v1/ai/indexing/{id}/cancel` | `ai:index:write` | 대기 중 작업 취소 |
| `GET /api/v1/ai/indexing/collections` | `ai:index:read` | 현재 환경 컬렉션 통계 |
| `POST /api/v1/ai/indexing/search` | `ai:index:search` | 현재 환경 컬렉션 유사도 검색 |

명세: [ai.openapi.json](https://platform.shnea.kr/integrations/ai.openapi.json), 서버 예제: [ai-client.py](https://platform.shnea.kr/examples/ai-client.py). 자료 조회는 로그인 없이 가능하다. 실제 API는 `X-Platform-Key`가 필요하다. 지원 목록의 `configured`는 설정 존재 여부이며 실제 공급자 호출 성공·한도·무료 이용을 보장하지 않는다.

`n8n.execute`·`usage`는 뇌대리 v12의 실제 서버 API에, `vector.index`는 v13의 PostgreSQL 색인 API에 연결한다. 기존 수동 n8n Qdrant 예제는 별개다. TTS·고자원 모델 관리·학습 데이터 수집/파인튜닝·실제 공급자 한도 순환·공통 결과 캐시는 여전히 미구현이다. 연결 설정·문서 조회와 실제 n8n·공급자·벡터 검색 검수 성공을 구분한다.

## PostgreSQL 문서 색인·전체 교체

호스트 서버가 문서 소유권을 확인하고 긴 문서를 청크로 나눈 뒤 `POST /api/v1/ai/indexing`에 보낸다. 플랫폼은 `project`·`environment`를 서버 키의 UUID로 고정하고 `collection`은 소문자 논리 이름으로 제한한다. 뇌대리의 저장·검색 범위는 `(owner_id, project, environment, collection)`이다. 이 색인은 기존 n8n Qdrant 컬렉션과 별도이며 한쪽에 넣은 문서가 다른 쪽 검색에 나타나지 않는다. n8n이 PostgreSQL 색인을 쓸 때는 뇌대리 검색 API의 결과를 작업 문맥으로 전달하도록 워크플로를 연결해야 한다.

```json
{
  "request_id": "portfolio-full-index-20261006-001",
  "collection": "portfolio",
  "mode": "replace_all",
  "sync": false,
  "documents": [
    {"id": "project-1-chunk-1", "title": "프로젝트 1", "content": "검색할 문서 내용", "metadata": {"source_id": "project-1"}}
  ]
}
```

`replace_all`은 지정 범위의 문서 전체를 **이 요청의 문서 목록으로 원자적으로 교체**한다. `documents: []`이면 지정 컬렉션의 문서를 모두 삭제한다. `delete_ids`는 이 모드에 넣지 않는다. 여러 요청으로 나눈 100건 초과 자료를 하나의 전체 교체로 처리할 수 없으므로 이 경우 현재 계약으로 기존 색인을 비우지 않는다. 추가·일부 교체는 `upsert`와 안정된 문서 ID를 쓰고, 특정 ID 삭제는 `delete`를 쓴다.

본문은 1MiB 이하, 문서와 삭제 ID는 각각 최대 100건이다. 문서 ID는 256자, 제목은 512자, 제목+본문 임베딩 텍스트는 16000자 이하다. 서버가 자동으로 청크를 나누지 않는다. `request_id`는 같은 업무 시도에서 고정하고 전송 실패 후 같은 내용으로만 재확인한다. 다른 내용은 409이며 실패·취소 후 새 실행에는 새 ID를 쓴다. `sync:false`는 접수 202, `sync:true`도 같은 컬렉션이 사용 중이면 202를 받을 수 있다. 상태 `pending → running → succeeded/failed`를 단건 GET으로 확인하고 대기 중 작업만 취소한다. 결과 요약은 플랫폼 접수 기준 뇌대리 기본 7일 뒤 만료되어도 실제 색인 문서는 `delete` 또는 `replace_all`까지 보존된다.

`POST /api/v1/ai/indexing/search`에는 `collection`, `query`, `limit`(1~50), `min_similarity`(-1~1)를 보낸다. 빈 컬렉션은 빈 결과다. `GET /api/v1/ai/indexing/collections`는 현재 프로젝트·환경의 컬렉션별 문서 수와 추정 토큰을 보여준다. 검색은 최대 10000문서 정확 검색이고 초과 범위는 뇌대리에서 422다. 토큰 수는 공백 단위 추정치이므로 공급자 청구량으로 취급하지 않는다. 색인 사용량은 뇌대리 usage에 별도 기록된다. 플랫폼은 문서 원문·벡터·검색 결과를 DB에 저장하지 않는다.

## AI 작업 실행·상태·취소

### 기존 AI 질문 기능 연결

뇌대리 v12부터 플랫폼은 `POST /api/v1/ai/jobs`로 질문·답변 생성 작업을 접수하고 결과를 전달한다. 이전 v11의 “플랫폼은 아직 질문·답변 생성 API를 제공하지 않습니다” 안내는 현재 계약에 적용하지 않는다. 기존 질문 화면과 호스트의 대화 저장·이용자 인증은 유지하고, 호스트 서버의 AI 호출을 이 API로 연결한다. 일반 질문은 `task_type: "chat.general"`, 검색 근거가 필요한 포트폴리오 질문은 `portfolio.search`를 사용하며 다른 작업을 일반 대화로 임의 대체하지 않는다.

새 질문마다 고정 `request_id`와 질문 `prompt`를 보내고 기존 대화·업무 지침은 해당 작업의 `input` 계약으로 전달한다. 서버 키에 `ai:execute`가 필요하며 비동기 결과 조회에는 `ai:jobs:read`도 필요하다. `succeeded`의 `result`를 기존 화면의 응답 계약으로 변환하고, `running`은 조회하며 `failed`/`cancelled`는 답변으로 표시하지 않는다. 공통 답변 문자열 필드를 임의로 가정하지 않는다. 뇌대리/n8n의 해당 작업 지침이 미설정이면 `awaiting_instructions`를 처리하고 지침을 먼저 등록한다. API 제공과 각 호스트의 대화 기능 연결 완료는 구분한다.

```json
{
  "request_id": "host-operation-20261005-001",
  "task_type": "portfolio.search",
  "prompt": "프로젝트의 기술 경험을 찾아 주세요.",
  "input": {"collection": "portfolio"},
  "sync": false
}
```

request_id는 비민감 업무 식별자로 공백만이 아닌 1~128자이며 제어문자를 거부한다. 원문·개인정보·키를 식별자에 넣지 않는다. 8종 task_type만 실행하고 미등록 작업을 chat.general로 대체하지 않는다. prompt는 공백 제외 1~200000자, 전체 본문은 2MiB다. input 객체에 기존 messages·이미지 참조·thread/memory·hash·context/system 등 작업별 데이터를 유지한다. 뇌대리는 prompt/input을 보관하고 n8n에 전달하므로 인증키·토큰·불필요한 개인정보를 넣지 않는다. 신뢰된 지침은 호스트 서버가 관리하며 최종 결과·태그/요약/댓글/UI 계약도 호스트가 검증한다.

project/environment는 **서버 키의 프로젝트 UUID·환경 UUID로 고정**한다. 생략을 권장하며 명시한 값은 키 범위와 같아야 한다. 목록/usage 쿼리에서 외부 project/environment 필터는 거부한다. 부가 input의 project/environment/owner_id/request_id/task_type/prompt/body/routing/provider_plan/cache 주장 필드도 거부한다. 서버가 확인한 모델 경로·소유권·cache hit를 요청자가 덮어쓰지 못하게 한다.

sync 기본 true는 같은 응답에서 상태/결과를 받는다. sync=false는 비동기 running 접수 후 GET 상태로 확인한다. 상태는 running/succeeded/failed/cancelled이고 result의 작업별 계약은 그대로 전달한다. 상태 조회·목록·usage는 현재 환경만 반환한다. 다른 환경의 UUID 조회·취소는 404이며 공유 upstream 키가 다른 소유자를 관리할 수 있어도 플랫폼은 권한을 먼저 확인한다. 취소는 `cancellation_requested:true,execution_stopped:null`이다. 뇌대리 v12는 상태 변경을 접수하며 실행 중 n8n HTTP/모델 프로세스 종료를 보장하지 않는다. 실제 종료 확인 전 같은 작업을 새 ID로 재실행하지 않는다.

호스트는 **새 업무 작업마다 하나의 request_id를 영속 보관**한다. 통신 재전송은 같은 ID·내용을 유지한다. 플랫폼은 환경별 ID·정규화 내용 SHA-256·원격 UUID·생성 시각만 저장하고 원문/벡터/결과는 저장하지 않는다. JSON 필드 순서와 sync 변경은 실행 내용 변경으로 보지 않는다. 같은 ID·다른 내용은 409 `AI_REQUEST_CONFLICT`, 같은 내용은 GET으로 기존 작업을 반환하며 reused=true다. 실패/취소 작업에도 실행 POST를 다시 보내지 않는다. 이는 뇌대리 v12의 실패 요청 재접수 시 재실행될 수 있는 동작을 제한한다. 서버 설정 없음·로컬 동시 한도 초과처럼 뇌대리에 전송하지 않았음이 확인된 거절만 원장 예약을 해제한다. request_id 중복 처리는 내용 캐시와 별개이며 공통 cache hit로 표시하지 않는다.

POST 응답이 유실되면 현재 환경의 최근 100건 목록에서 request_id를 찾아 연결한다. 찾지 못하면 409 `AI_REQUEST_UNCONFIRMED`다. 새 ID로 무조건 다시 실행하지 말고 접수·실행 상태를 운영자가 확인한다. 목록은 limit 1~100(기본50), 페이지네이션이 없으므로 이 복구 범위를 넘는 오래된 불명확 접수는 수동 확인이 필요하다. 플랫폼 원장은 환경당 10000건을 상한으로 두며 초과는 `AI_REQUEST_CAPACITY`다. 원격 작업 이력이 남아 있는 동안 식별자를 무작정 삭제해 다시 실행 가능하게 만들지 않는다.

결과 보관은 뇌대리 v12의 24시간 계약과 실제 expires_at을 따른다. 만료 시 플랫폼도 result=null·result_expired=true로 결과 노출/재사용을 막는다. 작업 이력·사용량과 플랫폼 식별 원장은 결과 만료와 구분한다. AI에는 파일 완료 웹훅/receipt가 없으며 비동기 상태는 요청한 작업 ID로 조회한다. 임의 완료 웹훅을 만들지 않는다.

```sh
python ai-client.py submit --input ./ai-job.json
python ai-client.py job --id <응답-id>
python ai-client.py jobs --status running --limit 50
python ai-client.py cancel --id <응답-id>
python ai-client.py usage --task-type portfolio.search --limit 50
```

[n8n AI 작업 호출 수동 예제](https://platform.shnea.kr/integrations/n8n-ai-jobs.sample.json)는 PLATFORM_URL과 Header Auth `X-Platform-Key`를 운영자가 선택한다. 실제 주소·키·Credential ID·자동 웹훅을 포함하지 않는다. 8종 중 하나와 고정 업무 request_id를 설정하며 기본 sync=true, 오류 시 중단·자동 재시도 없음이다. 내부 공급자 분기는 뇌대리의 [v12 라우팅 예제](https://github.com/shnea/noedaeri/blob/main/examples/n8n_ai_routing_sample.json)를 참조하되 기존 운영 워크플로를 자동 교체하지 않는다.

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

뇌대리의 [AI 분기 예제](https://github.com/shnea/noedaeri/blob/main/examples/n8n_ai_routing_sample.json)는 내부 수동/웹훅 워크플로 예제이며, 외부 앱은 이 JSON의 n8n 주소를 직접 호출하지 않고 플랫폼 AI 작업 API를 사용한다. 원래 운영 워크플로를 자동 교체하지 않는다.

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

## 전 서비스 공통 RAG·인덱싱

portfolio.search·document.analyze 등을 단일 공통 검색 노드로 연결한다. 질의 → Gemini 임베딩 → 동적 Qdrant 코사인 검색 → 작업별 retrieved_context/instruction 합성 → Raya → LangChain 모델 순서다. 논리 collection을 생략하면 task_type의 앞부분(portfolio/document/blog/code 등)을 사용한다. 플랫폼은 `[a-z][a-z0-9_-]{0,63}`의 논리 이름만 받으며 물리 이름은 `platform_<프로젝트UUID의하이픈제거>_<환경UUID의하이픈제거>_<논리이름>`이다. 다른 프로젝트·환경과 같은 논리 이름을 써도 컬렉션을 공유하지 않는다. 한 프로젝트 안의 최종 이용자별 소유권은 호스트가 검증하며 개별 이용자 비공개 자료를 공유 컬렉션에 무작정 넣지 않는다.

검색 문맥의 지시문은 참고 데이터이며 신뢰된 시스템 지침으로 실행하지 않는다. 검색 근거가 없으면 추측하지 않는다. 검색/인덱싱은 같은 모델·실제 차원·전처리로 맞춘다. 모델이 같아도 공통 API 기본 768과 n8n 네이티브의 기본 출력 차원을 동일하다고 가정하지 않는다. 확인한 [n8n 공식 Gemini 구현](https://github.com/n8n-io/n8n/blob/master/packages/%40n8n/nodes-langchain/nodes/embeddings/EmbeddingsGoogleGemini/EmbeddingsGoogleGemini.node.ts)은 outputDimensionality를 전달하지 않는다. 네이티브 경로는 `gemini-embedding-001`의 3072차원 출력과 검색을 맞추며 설치 버전/실제 벡터를 검수한다. 768을 쓰려면 검색과 인덱싱 모두 차원을 지정할 수 있는 공통 API 연결을 사용한다.

[뇌대리 범용 예제](https://github.com/shnea/noedaeri/blob/main/examples/n8n_vector_indexing_sample.json)는 문서 → RecursiveCharacterTextSplitter 500자/50자 중복 → `googlePalmApi` Gemini → `qdrantApi` Qdrant insert 흐름이다. [플랫폼 범위 적용 수동 예제](https://platform.shnea.kr/integrations/n8n-vector-indexing.sample.json)는 integration:read 키의 context로 물리 컬렉션을 생성하고 새 3072/Cosine 컬렉션을 읽어 확인한 뒤 네이티브 노드에 연결한다. 문서를 한 건씩 처리해 [sub-node 표현식의 첫 항목 참조](https://docs.n8n.io/integrations/builtin/cluster-nodes/sub-nodes/n8n-nodes-langchain.documentdefaultdataloader)로 문서가 중복되는 것을 막는다. 모든 Credentials는 운영자가 선택하며 JSON에는 Credential ID·키·주소·공개 웹훅이 없다.

플랫폼 예제는 컬렉션을 생성/삭제하거나 기존 인덱스를 덮어쓰지 않는다. 호스트가 문서 소유권을 확인한 뒤 새 컬렉션을 준비하고 모델·차원·전처리 기록과 조회 대조 검수를 수행한다. 변경/삭제 문서의 안정된 ID·중복·보존 정책과 대량 입력 분할은 운영 연결 전에 확정한다. 네이티브 insert 예제를 멱등 업서트·자동 정리 완료로 표시하지 않는다. 기존 text-embedding-004 컬렉션은 새 모델과 혼합하지 않고 검수 후 전환한다. 실제 n8n import/검색/인덱싱은 해당 운영 환경에서 별도로 검증한다.

## usage·보존·실패

사용량 저장/조회는 뇌대리, 실제 모델 호출 보고는 n8n 책임이다. 플랫폼은 GET /api/v1/ai/usage로 키의 현재 프로젝트/환경·task_type·limit(1~200·기본50)를 고정해 summary/records를 조회한다. 내부 보고 `/internal/ai/usage`는 워커/실행 키용이며 외부 호스트나 플랫폼 공개 API에 노출하지 않는다. 기존 앱 수집 콜백은 실제 집계 대조 검수 뒤 전환한다.

summary에는 provider/model/task_type별 call_count·total_prompt_tokens·total_completion_tokens·total_tokens, records에는 요청·작업 식별자와 보고 토큰/시간을 제공한다. 뇌대리 v12 중복 키는 (project,environment,request_id,provider,model)이며 같은 보고를 1회 반영한다. 같은 요청에서 같은 provider/model을 여러 번 실제 호출했을 때 각각 식별하는 계약은 아직 없다. 공개 n8n 예제의 결과 정리에는 글자 수/4 기반 토큰 추정과 추천 등급 기반 provider/model 매핑이 남아 있으므로 실제 사용량이라고 단정하지 않는다. 플랫폼은 값의 범위와 소유권을 검증해 `measurement:upstream_reported_unverified`를 명시한다. 실제 공급자 usage·최종 모델·Agent 각 호출과 대조하기 전 비용·무료 잔여 한도·완전한 실측으로 표시하지 않는다.

인증된 서비스·소유권의 request_id/task_type에 공급자 호출 ID 또는 실행·노드·차수·항목 식별자를 연결한다. 동일 보고 재전송은 1회만 반영하고 새 실제 호출은 별도 사용량이다. 추천/최종 등급·대체 사유·실제 provider/응답 model·재시도·실패 전 발생한 호출·시간을 구분한다. 모델 미확인은 미확인, 실제 입력/출력/총 토큰만 집계한다. 추정치를 실제 사용량에 더하지 않는다. Agent의 여러 호출도 각각 기록한다.

관리자는 전체, 연결 서비스는 플랫폼을 통해 자기 요청만 조회한다. 원문·지침·응답 전문·인증 토큰·임의 콜백 URL을 usage에 저장하지 않는다. Raya CPU 추론/입력 토큰은 운영 지표이며 공급자 토큰/비용에 합산하지 않는다. 무료 모델도 기록하되 비용·잔여 무료 한도를 토큰 수만으로 단정하지 않는다. 수집 실패와 AI 실패는 구분하고 보고 재시도로 모델을 재호출하지 않는다. 학습 원문·결과 검토·접근·보존·내보내기·파인튜닝은 별도 미구현이다.

플랫폼 동기 API는 연결 5초·요청 100초·전체 대기 105초, 인스턴스당 동시 2개다. 원격 응답은 Raya 64KiB·임베딩 16MiB 상한이며 HTTPS/TLS 검증과 리다이렉트 금지를 적용한다. 파일 작업 큐·웹훅·receipt는 사용하지 않는다. AI 비동기 실행과 24시간 결과는 뇌대리가 관리한다. AI 단건 응답 2MiB·목록 16MiB·usage 1MiB를 제한한다. 자동 재시도·등급/모델 대체를 하지 않는다.

| HTTP / code | 처리 |
| --- | --- |
| 401 `INVALID_API_KEY` | 환경/프로젝트·키 만료/폐기 확인 |
| 403 `INSUFFICIENT_SCOPE` | 해당 AI 권한의 새 키 발급 |
| 404 `AI_JOB_NOT_FOUND` | 다른 환경 또는 없는 작업. ID를 추측해 재호출하지 않음 |
| 409 `AI_REQUEST_CONFLICT` / `AI_REQUEST_UNCONFIRMED` / `AI_REQUEST_CAPACITY` | ID·내용·접수 여부·이력 한도를 확인하고 자동 재실행하지 않음 |
| 410 `AI_RESULT_EXPIRED` | 결과 보관 만료. 새 요청은 별도 검토 |
| 413 `PAYLOAD_TOO_LARGE` | 본문을 축소 |
| 422 `AI_INVALID_REQUEST` | 필드·텍스트·등록 모델·차원·배치 또는 공급자 입력 제한 확인 |
| 429 `AI_BUSY` | 동시 실행/공급자 한도 확인 후 제한적으로 새 요청 판단. 토큰 전체 소진과 구분 |
| 502 `AI_UPSTREAM_AUTH_FAILED` / `AI_INVALID_RESPONSE` | 운영자에게 서버 인증/결과 계약 확인 요청 |
| 503 `AI_NOT_CONFIGURED` / `AI_UNAVAILABLE` | 서버 설정·지원 API·연결·자원 상태 확인 |
| 504 `AI_TIMEOUT` | 종료 상태를 확인하고 무조건 재호출 금지 |

오류는 RFC 9457 `code`·한국어 detail·requestId로 처리한다. 뇌대리 오류 본문·키·주소를 그대로 노출하지 않는다. 문서 조회·명세 등록·예제 import와 실제 서버/공급자 호출·n8n 운영 전환·벡터 검색·사용량 검수 성공을 각각 구분해 보고한다.
