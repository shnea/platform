# 공통 로그 연결

API: `https://platform.shnea.kr/api/v1/logs`
명세: https://platform.shnea.kr/integrations/logs.openapi.json
실행 예제: https://platform.shnea.kr/examples/logs-client.py (Python 3.10+, 표준 라이브러리)

## 인증·호출

호스트 서버의 `X-Platform-Key`: 전송 `logs:write`, 조회 `logs:read`. 기존 키에는 자동 추가되지 않는다.
프로젝트·환경은 인증 키로 결정한다. 본문·`X-Scope-OrgID`로 범위를 선택하지 않는다. 키를 브라우저·앱에 넣지 않는다.

`POST /api/v1/logs` → 202 `{accepted:1}`
```json
{"entries":[{"service":"order-api","level":"ERROR","message":"주문 처리 실패","requestId":"request-123","traceId":"trace-123","errorCode":"PAYMENT_BUSY","attributes":{"orderId":"order-123"}}]}
```
`timestamp` 생략 시 수신 시각. 지정 시 ISO 8601, 최근 1시간~미래 60초. `exception` 문자열 선택.
수준은 `TRACE/DEBUG/INFO/WARN/ERROR/FATAL`. 서비스는 영문·숫자로 시작하는 영문·숫자·`_.-` 64자 이하.
요청·Trace·오류 ID는 같은 문자 128자 이하. 서로 다른 서비스에서도 같은 요청의 ID를 전달해 조회한다.

`GET /api/v1/logs?service=order-api&level=ERROR&requestId=request-123`
검색: `from/to` ISO 시각, `service/level/text/requestId/traceId/limit`. 최근 7일 안에서 최대 24시간 범위, 기본 최근 1시간·100건, 최대 200건. 응답 `items/limited/from/to/retentionDays/dailyBytesLimit`.
`limited:true`면 최신 N건에 도달했으므로 기간을 줄여 조회한다. 전체 건수·완전한 내보내기를 뜻하지 않는다.

## Python 연결

```python
# 내려받은 logs-client.py를 logs_client.py로 저장
import logging, os
from logs_client import PlatformLogs
handler = PlatformLogs(os.environ['PLATFORM_BASE_URL'], os.environ['PLATFORM_API_KEY'], 'order-api')
logger = logging.getLogger('order')
logger.setLevel(logging.INFO)
logger.addHandler(handler)
logger.info('주문 처리 완료', extra={'requestId':'request-123', 'attributes':{'orderId':'order-123'}})
# 종료 시: handler.close(); 운영 지표로 handler.stats()의 dropped/failed_batches/queued 확인
```

## 제한·실패 처리

- 환경별 7일 보존, UTC 하루 10 MiB·10,000회·분당 120회 전송. 하루 한도는 저장 요청으로 직렬화된 마스킹 결과 바이트 기준이며 실패·결과 불명확 전송도 차감한다.
- 배치 1~100건/256 KiB, 건당 16 KiB. 메시지·예외 각각 8,192자, 속성 깊이 5/객체·배열당 32항목. 초과 400/413, 할당량 초과 429, 저장소 응답 불명확 503.
- 예제는 최대 1,000건(건당 직렬화 12 KB) 대기, 최대 10건 배치, 요청 5초, 최대 3회 전송. 꽉 찬 대기열은 새 로그를 버리고 `dropped`를 증가시킨다. 종료 대기는 최대 6초이며 남은 로그는 유실될 수 있다.
- 401/403/400/413은 자동 재시도하지 않는다. 429/5xx/통신 장애는 제한 재시도한다. 재전송 시 중복될 수 있으므로 로그를 결제·발송 같은 업무 처리의 근거로 사용하지 않는다.
- 비밀번호·API 키·Authorization·Cookie·인증 코드·전체 요청/응답 본문·불필요한 개인정보를 송신 전에 제외한다. 예제와 수집 서버가 알려진 민감 키/토큰/이메일을 마스킹하지만 자유 형식 모든 비밀·개인정보의 자동 탐지를 보장하지 않는다.
- 로그는 내부 전용 Loki에 저장한다. 7일 이전은 API 검색에서 제외하고, 저장 청크는 주기적 보존 작업과 삭제 지연(기본 2시간)을 거쳐 물리 삭제한다. 보존 기간이 전체 디스크 사용량 상한은 아니므로 환경 수·색인·WAL·삭제 지연을 포함해 디스크 여유를 관리한다.
- 관리자 **로그** 메뉴에서 조건 검색·상세·같은 요청/Trace와 조회된 결과의 오류 분포를 확인한다. 감사 이력은 별도이며 게스트는 조회할 수 없다.
