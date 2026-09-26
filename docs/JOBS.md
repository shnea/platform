# 환경 반영 Job

현재 Job은 Keycloak 환경 반영(`ENVIRONMENT_PROVISION`)에만 사용한다. 기존 환경 생성·수정·동기 반영 API는 그대로다. 새 작업은 관리자 JWT로 명시적으로 접수한다. 서버 API 키나 일반 회원에게 관리 권한을 주지 않는다.

## API와 상태

| 메서드·주소 | 동작 |
| --- | --- |
| `POST /api/v1/admin/environments/{id}/provision-jobs` | 현재 환경 revision으로 작업 접수. HTTP 202는 접수 결과이며 완료 보장이 아니다 |
| `GET /api/v1/admin/jobs` | `environmentId`, `state`, `limit`(기본 20, 최대 100), `offset`(최대 1,000,000)으로 조회 |
| `GET /api/v1/admin/jobs/{id}` | 작업 상태·시도 횟수·시도 이력·실패 코드·요청 ID 조회 |
| `POST /api/v1/admin/jobs/{id}/cancel` | QUEUED/RETRY_WAIT 취소. 이미 CANCELLED면 그대로 반환. 실행 중 작업을 강제 중단하지 않음 |
| `POST /api/v1/admin/jobs/{id}/retry` | FAILED 작업의 이력을 보존하고 현재 revision으로 후속 작업 접수 |

상태는 `QUEUED → RUNNING → SUCCEEDED`이며 실패하면 `RETRY_WAIT → RUNNING`, 최대 횟수에 도달하면 `FAILED`다. 대기 취소나 실행 전 환경 revision 변경은 `CANCELLED`로 끝난다. READY 환경에 접수한 작업은 인증 설정을 다시 쓰지 않고 성공 처리한다. 중지된 프로젝트를 반영할 때도 인증 영역은 비활성 상태를 유지한다.

환경당 활성 작업은 하나다. 중복 접수는 기존 활성 작업을 반환한다. 같은 실패 작업의 재접수는 한 개의 후속 작업만 만들고 반복 요청에는 그 작업을 반환한다. 별도 활성 작업이 있으면 그 작업을 반환하므로 `retryOf`를 확인한다. 완료된 후속 작업을 다시 실행하려면 일반 접수 API를 사용하며, 후속 작업이 실패했으면 그 작업을 재시도한다.

HTTP 오류 `JOB_STATE_CHANGED`, `JOB_NOT_CANCELLABLE`, `JOB_NOT_RETRYABLE`은 모두 409다. 작업 내부 실패 코드는 다음과 같다.

| 코드 | 의미 |
| --- | --- |
| `ENVIRONMENT_PROVISION_FAILED` | 인증 서버 반영 실패. 제한된 자동 재시도 대상 |
| `JOB_EXECUTION_FAILED` | 실행 트랜잭션의 예상하지 못한 실패. 원문 예외는 응답에 포함하지 않음 |
| `WORKER_INTERRUPTED` | 점유 만료로 이전 시도를 ABANDONED 처리 |
| `RETRY_EXHAUSTED` | 복구 시 이미 최대 시도 횟수에 도달 |
| `JOB_TARGET_CHANGED` | 접수 뒤 환경 설정이 바뀌어 이전 작업을 취소 |

## 실행·복구와 기록

- 프로젝트 서비스 내부 워커가 시작 5초 뒤부터 실행 종료 후 2초 간격으로 확인한다. 프로세스당 한 작업을 실행한다. 총 3회 시도하며 실패 후 10초·30초를 기다린다. 기존 FAILED 환경을 임의로 접수하지 않는다.
- 점유는 60초다. 실행 중에는 작업 행을 잠그므로 시간이 지나도 다른 워커가 그 행을 재실행하지 않는다. 프로세스 종료로 실행 트랜잭션이 롤백되면, 커밋돼 있던 RUNNING 점유가 만료된 뒤 복구한다. 점유 토큰이 달라진 이전 실행자의 완료·실패 기록은 무시한다.
- 환경 반영은 프로젝트 잠금 아래에서 수행한다. 외부 Keycloak 반영과 DB 커밋은 하나의 원자적 작업이 아니므로, 장애 후 같은 인증 영역을 다시 확인·반영할 수 있다. 기존 `ensureRealm`의 소유권 확인과 재실행 가능한 반영을 사용한다. 외부 호출의 정확히 한 번 실행을 보장하지 않는다.
- 접수·실행·대기·복구·완료·취소를 감사 이력에 남긴다. 요청 ID는 작업과 Outbox에 저장하고 실행 로그에 이어 준다. 관리자 취소·재시도 요청 자체는 HTTP 추적에 별도로 남는다.
- 최종 상태와 `project_outbox` 행은 같은 DB 트랜잭션에서 커밋한다. 이벤트는 `job.succeeded`, `job.failed`, `job.cancelled`, `schema_version=1`이며 상태·실패 코드만 payload에 포함한다. **전달 워커·소비자는 아직 없으므로 PENDING 저장까지만 구현됐다.**
- `PLATFORM_JOBS_ENABLED=false`를 적용하고 프로젝트 서비스 컨테이너를 재생성하면 새 실행·복구를 멈춘다. 접수·조회·취소와 기존 동기 API는 유지된다. 현재 이력은 자동 삭제하지 않는다. 보존 기간·기간별 검색·관리 화면·운영 알림은 후속 범위다.

## 재현 가능한 검증

```powershell
# 프로젝트 이름을 명시한다. 로컬 .env의 COMPOSE_PROJECT_NAME보다 -p가 우선한다.
docker compose -p platform-job-checks -f compose.jobs-test.yml up --abort-on-container-exit --exit-code-from check
docker compose -p platform-job-checks -f compose.jobs-test.yml down

# 개발 스택에 새 이미지를 반영한 뒤 실행한다.
docker compose -f compose.yml -f compose.dev.yml --profile test run --rm --no-deps api-check python /checks/check-jobs-api.py
```

첫 검사는 별도 PostgreSQL의 테스트별 스키마에 V1~V7을 적용하고 실제 JDBC·트랜잭션·워커 코드를 검증한다. 인증 서버만 모의 처리한다. 중복 접수·동시 점유·지연된 실행·재시도 간격/한도·수동 재시도·만료 점유 복구·이전 점유 차단·revision 취소·Outbox 실패 롤백·중지 상태 유지·MDC 복원을 포함한다. 복구는 커밋된 점유를 남기고 새 워커 객체로 실행하는 방식이며 실제 OS 프로세스 강제 종료 검사는 아니다. 전체 단위 검사와 실제 보안 필터의 일반 사용자 403 검사도 함께 수행한다.

테스트 DB는 호스트 포트와 영속 데이터 볼륨이 없는 임시 DB다. 기존 Compose와 합치지 않는다. Gradle 캐시만 재사용하며 검사 결과는 Git에서 제외한 `output/job-checks/results`에 남긴다. 일반 이미지 빌드에서는 DB 검사가 생략되므로 위 별도 검사를 반드시 실행해야 한다.

두 번째 검사는 DEV 전용이며 자체 프로젝트·인증 영역을 만들어 READY 환경의 Job 완료, 스케줄러, 명세와 응답, 감사 이력, 오류·인증 경계를 확인한다. 끝나면 자체 프로젝트를 중지하고 관리자 로그인 세션을 종료한다. 생성한 프로젝트·Job·감사 이력은 검수 증거로 남긴다. 실패 재시도·취소·동시성은 첫 격리 검사에서 검증한다. 외부 이메일은 보내지 않는다.

동일한 관리자 계정을 사용하는 API 검사는 동시에 실행하지 않는다. 앞 검사에서 생성한 세션을 종료한 뒤 다음 검사를 실행한다.
