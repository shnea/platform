# Job 이벤트 전달

현재는 프로젝트 서비스의 `job.succeeded`, `job.failed`, `job.cancelled`와 적체 감시의 `job.backlogged`, `job.backlog_recovered`, `job.backlog_closed`를 알림 서비스에 전달한다. 메시지 브로커는 없고 각 서비스는 자기 DB만 사용한다. [운영 알림 화면](OPERATIONAL_ALERTS.md)에서 조회·확인·이메일 설정을 관리한다. 외부 웹훅은 후속 범위다.

## 계약과 수신 처리

Job 최종 상태와 `project_outbox`는 같은 트랜잭션에 저장한다. 워커는 고정 내부 주소 `POST http://notification-service:8080/internal/v1/events/jobs`로 다음 필드를 전달한다. 사용자 입력으로 전달 URL을 지정할 수 없다.

| 필드 | 의미 |
| --- | --- |
| `id`, `type`, `schemaVersion` | 영속 이벤트 ID, 위 여섯 가지 유형, 현재 지원 버전 `1` |
| `source` | `project-service` |
| `projectId`, `environmentId` | 발행한 프로젝트·환경 범위 |
| `targetId`, `targetRevision` | Job ID와 접수 당시 환경 revision. 적체 이벤트에서는 적체 감시 설정 revision |
| `requestId` | 최종 Job 이벤트는 접수 요청 ID, 적체 이벤트는 감시기가 생성한 추적 ID |
| `causationId` | 적체 해소·종료는 원래 적체 발생 이벤트 ID, 나머지는 `null` |
| `occurredAt` | Outbox 생성 시각 |
| `payload` | 상태(`SUCCEEDED`/`FAILED`/`CANCELLED`/`BACKLOGGED`/`BACKLOG_RECOVERED`/`BACKLOG_CLOSED`), 허용된 `errorCode` 또는 `null` |

비밀키·토큰·메일 본문·사용자 파일은 넣지 않는다. 알림 서비스는 별도 `X-Platform-Event-Key` 인증을 통과한 요청만 받으며 이메일 인증키로 이벤트 API를 호출할 수 없다. 지원하지 않는 버전은 422, 형식 오류는 400이다. 외부 Nginx에는 내부 이벤트 경로를 공개하지 않는다.

수신 이벤트와 `job.failed`의 운영 알림 기록(`operational_alerts`, `JOB_FAILED`)은 알림 DB의 같은 트랜잭션으로 저장한다. 이후의 성공은 이전 실패의 복구 연결과 `JOB_RECOVERED` 알림을 만들며, 취소는 수신 기록만 남긴다. 환경별 잠금으로 순서 판단·중복 이메일 억제·발송 대기 등록까지 함께 커밋한다. 수신 HTTP 안에서는 외부 발송하지 않으며 별도 워커가 환경의 이메일 수신 설정에 따라 처리한다. 개발은 모의 발송만 허용한다. 중지된 프로젝트의 과거 결과도 기록할 수 있으며 로그인 설정·프로젝트 상태는 바꾸지 않는다. 세부 정책은 [운영 알림 지침](OPERATIONAL_ALERTS.md)을 따른다.

같은 ID·같은 내용은 기존 결과 `{id, state: "ACCEPTED"}`를 반환한다. 같은 이벤트 ID에 프로젝트·환경 등 다른 내용을 붙이거나 같은 Job의 최종 결과를 새 이벤트 ID로 다시 보내면 409다. 적체는 같은 Job에서 여러 번 생길 수 있어 발생 이벤트 ID를 사건 단위로 삼고, 해소/종료는 사건당 하나만 허용한다. 사건 잠금·범위/Job 검사로 역순·동시 수신의 충돌도 차단한다. 최종 결과의 Job 유일성은 부분 인덱스로 유지한다. 수신은 이력을 추가하는 방식이므로 순서가 뒤집혀도 최신 환경 상태를 덮어쓰지 않는다.

## 전달·복구 정책

- `PENDING → DELIVERED` 또는 `FAILED`. 실행자는 60초 점유와 토큰으로 구별한다. 프로세스당 2초 주기마다 한 건을 처리한다. 기존 Job 워커와 기본 스케줄러를 공유하므로 실제 주기는 처리 시간만큼 늘어날 수 있다.
- HTTP 연결 제한 3초·응답 제한 5초. 호출 동안 이벤트 행을 잠가 다른 워커의 동시 실행을 막는다. 수신 성공과 발행 측 완료 기록은 하나의 트랜잭션이 아니므로 중복 전달은 가능하다.
- 최대 5회, 다음 시도까지 10·30·120·300초. 연결 실패·응답 유실·잘못된 확인 응답·5xx·408·429는 재시도한다. 그 밖의 4xx는 즉시 최종 실패다. 응답 본문과 예외 원문은 보관·로그 출력하지 않는다.
- 재시작 뒤 만료 점유는 `ABANDONED`로 기록하고 이어서 실행한다. 이전 점유 토큰은 완료나 실패를 갱신하지 못한다. 마지막 시도 중 중단됐어도 한도를 넘겨 자동 실행하지 않는다.
- 수신 확인의 이벤트 ID와 `ACCEPTED`를 검사한 뒤 완료로 기록한다. 확인 응답을 잃어도 동일 이벤트를 재전송하므로 수신 부작용은 중복되지 않는다. 새 워커 객체로 재개하는 DB 검증은 수행했으며 실제 OS 프로세스 강제 종료 검수는 별도다.
- 수동 재전송은 같은 이벤트·내용과 누적 이력을 보존하고 이번 재시도 횟수만 0으로 되돌린다. 원래 Job 실행을 다시 요청하는 기능과 구분한다. 전달/수신/운영 알림 기록은 아직 자동 삭제하지 않는다.

## 관리 API

관리자 JWT만 허용하며 일반 회원·서버 API 키에는 열지 않는다.

주요 메뉴의 비동기 작업 → 프로젝트·환경 선택 → 작업 상세의 **이벤트 전달**에서 확인한다. 프로젝트 개요의 **작업 보기**에서도 선택 범위를 유지해 이동한다. 작업 자체의 실행 결과와 이벤트 전달 결과를 구분하고, 이벤트 ID·이번 접수/누적 횟수·완료 시각을 표시한다. **전달 시도 이력**을 펼치면 최신 100개까지 오류 설명·HTTP 상태·시작/종료 시각을 볼 수 있다.

최종 실패에서 **실패 이벤트 재전송**을 누르고 환경·영향을 확인하면 같은 이벤트를 다시 접수한다. Job을 다시 실행하거나 환경 설정을 바꾸지 않는다. 진행 중 상태는 기존 자동 갱신 설정에 따라 5초마다 확인한다. 확인창과 숨겨진 탭에서는 조회를 멈추며 수동 **전달 상태 새로고침**도 제공한다. 조회 오류 때는 이전 전달 정보를 숨겨 오래된 상태로 조치하지 못하게 한다. 재전송 결과가 불명확하면 확인창의 재제출을 막으므로 닫고 상태부터 다시 확인한다.

| API | 동작 |
| --- | --- |
| `GET /api/v1/admin/jobs/{id}/events` | 해당 Job의 최종 결과·적체 이벤트와 이벤트별 최근 100개 시도 조회. 아직 이벤트가 없으면 빈 배열 |
| `POST /api/v1/admin/events/{id}/retry` | FAILED 이벤트 재전송 접수(202). PENDING이면 그대로 반환, DELIVERED면 409 `EVENT_NOT_RETRYABLE`. `event.requeued` 감사 기록 |

전달 오류는 `DELIVERY_UNCONFIRMED`, `DELIVERY_UNAVAILABLE`, `DELIVERY_REJECTED`, 복구 오류는 `DELIVERY_INTERRUPTED`, `DELIVERY_EXHAUSTED`다. HTTP 상태도 시도별로 남는다. 202는 접수 결과이며 전달 성공을 뜻하지 않는다.

## 설정·검증

`.env.example`과 `python scripts/init-env.py --upgrade`가 `PLATFORM_EVENTS_SECRET`을 생성한다. 기존 값은 바꾸지 않는다. 키는 프로젝트·알림 서비스에만 전달하며 이메일 키와 분리한다. 이미 설정된 빈 값은 자동 교체하지 않으므로 운영자가 채운다.

신규 환경 예시는 `PLATFORM_EVENTS_ENABLED=true`다. 키를 준비하지 않은 기존 배포는 Compose 기본값 `false`로 전달 워커를 멈춘 채 기존 API를 유지한다. 활성화 시 32자 이상 키가 없으면 프로젝트 서비스 시작을 거부한다. 알림 서비스도 미설정 키로 들어오는 이벤트를 거부한다. 최초 활성화 시 기존 PENDING 이력도 순차 전달한다. 알림 서비스를 먼저 갱신한 뒤 프로젝트 서비스를 적용한다.

`PLATFORM_EVENTS_ENABLED=false`로 프로젝트 서비스를 재생성하면 새 전달을 멈춘다. Job 실행과 Outbox 저장, 관리자 조회·재전송 접수는 유지한다. 키를 회전할 때는 전달을 멈추고 양쪽 키를 교체한 후 재개한다.

```powershell
# 기존 개발 DB와 분리된 임시 DB. 프로젝트 이름을 반드시 지정한다.
docker compose -p platform-job-checks -f compose.jobs-test.yml up --abort-on-container-exit --exit-code-from check
docker compose -p platform-job-checks -f compose.jobs-test.yml down

# 갱신한 개발 스택에서 실제 관리자 인증·Job→Outbox→알림 수신 확인
docker compose -f compose.yml -f compose.dev.yml --profile test run --rm --no-deps api-check python /checks/check-jobs-api.py
```

첫 검사는 서비스별 독립 스키마와 실제 PostgreSQL을 사용한다. 발행 측은 로컬 HTTP 서버로 응답 유실·실패·점유 경합을 주입하고, 수신 측은 실제 트랜잭션으로 동시 중복·충돌·알림 저장 실패 롤백·순서 역전·인증 경계를 검증한다. 두 번째 검사는 실제 두 서비스와 스케줄러를 거쳐 전달 완료를 확인한 뒤 자체 프로젝트를 중지하고 로그인 세션을 종료한다. 외부 발송은 없다.
