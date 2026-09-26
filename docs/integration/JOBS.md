# 외부 Job 연결

API: `https://platform.shnea.kr/api/v1/jobs`
명세: https://platform.shnea.kr/integrations/jobs.openapi.json
실행 예제: https://platform.shnea.kr/examples/jobs-client.py (Python 3.10+, 표준 라이브러리)

## 인증

호스트 서버에 `PLATFORM_BASE_URL=https://platform.shnea.kr`, `PLATFORM_API_KEY` 주입.
`X-Platform-Key` 사용. 등록·취소·재접수 `jobs:write`, 조회 `jobs:read`, 워커 `jobs:work`를 구분한다. 기존 키에는 새 권한이 자동 추가되지 않는다. 프로젝트·환경은 키에서 결정한다.

## 호출 순서

| 목적 | 호출 | 입력·결과 |
| --- | --- | --- |
| 등록 | `POST /jobs` | `requestId` UUID, `queue`, `payload` 객체 → 202 Job |
| 가져오기 | `POST /jobs/claim` | `queue`, `workerId` → `{job,leaseToken}` 또는 204 |
| 점유 연장 | `POST /jobs/{id}/heartbeat` | `X-Job-Lease`, `{progress:0..100}` → Job |
| 완료 | `POST /jobs/{id}/complete` | `X-Job-Lease`, `{result:{...}}` → Job |
| 실패 | `POST /jobs/{id}/fail` | `X-Job-Lease`, `{errorCode:"HOST_BUSY",retryable:true}` → Job |
| 목록·상세 | `GET /jobs`, `GET /jobs/{id}` | 목록 `items/hasMore`, 상세 `job/attempts` |
| 취소·재접수 | `POST /jobs/{id}/cancel`, `POST /jobs/{id}/retry` | 대기 취소 / 최종 실패의 새 Job |

표의 경로 앞에 `/api/v1`을 붙인다. `queue`는 영문·숫자 시작, 영문·숫자·`_.-` 64자 이하. `workerId`는 같은 문자 100자 이하.

```python
# 내려받은 jobs-client.py를 jobs_client.py로 저장
import os, uuid
from jobs_client import Jobs
producer = Jobs(os.environ['PLATFORM_BASE_URL'], os.environ['PRODUCER_KEY'])
request_id = uuid.uuid4()  # 먼저 호스트 DB에 입력과 함께 저장
job = producer.submit('report', {'reportId': 'report-123'}, request_id)
worker = Jobs(os.environ['PLATFORM_BASE_URL'], os.environ['WORKER_KEY'])
def handle(job, lost):
    # 실제 업무: 호스트 DB에서 job['id']의 완료 여부 확인 + 업무 변경을 같은 트랜잭션에 기록
    if lost.is_set():
        raise RuntimeError('lease lost')
    return {'reportId': job['payload']['reportId']}
worker.run_once('report', 'worker-1', handle)
```

## 반드시 지킬 규칙

- 플랫폼은 대기열·상태를 관리한다. 실행 코드는 호스트 워커에 둔다. 코드 업로드·플랫폼 원격 실행·임의 URL 호출 기능은 없다.
- **최소 한 번 실행**이다. 등록 중복 방지와 업무 중복 방지는 다르다. 호스트는 `job.id`로 DB 변경을 멱등 처리하고, 결제·발송은 제공자의 중복 방지 키도 사용한다. 수동 재접수는 새 ID이므로 업무 대상 ID도 확인한다.
- 등록 타임아웃 시 **같은 requestId + 같은 입력**을 재전송한다. 같은 ID의 다른 입력은 409. 30일 이력 삭제 후에는 같은 ID의 재등록을 막지 못하므로 장기 중복 방지는 호스트가 맡는다.
- 점유는 60초, 20초마다 heartbeat. `leaseToken`은 해당 시도의 비밀값이다. 409 `EXTERNAL_JOB_LEASE_LOST`이면 추가 부작용·보고를 중지한다. 점유 확인 실패 시에도 보수적으로 실행을 중지한다.
- 완료/실패 보고는 같은 토큰·같은 결과만 재전송한다. 최대 시도 기본 3/최대 10, 일시 실패 재시도 지연 10·20·…·최대 60초. 만료 복구는 10초 뒤 재대기. 결과가 불명확한 외부 발송은 `retryable:false`로 보고하고 수동 확인한다.
- 빈 큐는 최소 2초 후 다시 조회한다. 네트워크·5xx 재시도는 횟수·지연 상한을 둔다. 제공 예제는 보고 최대 3회, 업무 예외는 기본 재시도하지 않는다.
- `payload/result` 객체 각각 16 KiB. 비밀값·파일 본문 대신 업무 식별자만 담는다. 환경별 활성 1,000건/보관 10,000건, 완료·취소·최종 실패 이력 30일. 한도 초과 429.
- `runAfter`는 최대 7일 뒤, 실행 시각 보장이 아닌 가장 이른 실행 가능 시각이다. 관리자 **비동기 작업 → 외부 프로젝트 작업**에서 이력·결과·취소·재접수를 확인한다.
