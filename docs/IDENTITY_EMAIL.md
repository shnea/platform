# 인증 이메일과 개발 수신함

Keycloak의 기본 이메일 인증·비밀번호 찾기·비밀번호 변경 화면을 사용한다. Keycloak이 서명한 링크와 만료·사용 여부를 검증하고, 이메일 송신 SPI가 알림 서비스로 전달한다. 별도 비밀번호·복구 토큰 저장소는 만들지 않는다. 플랫폼 관리자 realm의 복구와 일반 업무 알림은 이 기능의 대상이 아니다.

## 실행과 모드

기존 `.env`는 보존하고 `python scripts/init-env.py --upgrade`로 `PLATFORM_MAIL_SECRET`을 추가한다. 호스트 Python이 없으면 [PC 인계 안내](HANDOFF.md)의 Python Docker 명령에 `--upgrade`를 붙인다. 세 서비스에 같은 32자 이상 비밀값을 전달하며 값 교체 시 세 컨테이너를 함께 재생성한다. 실제 `.env`는 커밋하지 않는다.

```sh
docker compose -f compose.yml -f compose.dev.yml up -d --build --wait --wait-timeout 300
```

| 배포 모드 / 환경 | 동작 |
|---|---|
| `dev` / `DEV` | 외부 발송 없이 DB에 모의 메일 저장. 관리자 수신함에서 조회 |
| `prod` / `PROD` | NCP 키·발신 설정이 준비되면 NCP로 전달 |
| 나머지 조합 | 이메일 옵션 활성화·발송 거부 |

NCP IAM 키·발신자는 [NCP 설정 안내](NOTIFICATION_SETUP.md)를 따른다. 현재 어댑터는 `https://mail.apigw.ntruss.com/api/v1/mails`만 허용하고 리다이렉트를 따르지 않는다. SMTP 설정은 사용하지 않는다. NCP 설정 존재와 알림 서비스 응답은 준비 상태이며 IAM 권한·발신 승인·실제 수신 성공 검증은 별도다. 기존 환경의 정책·계정·SMTP 설정을 자동으로 수정하지 않는다.

## 관리자 검수

1. 활성 프로젝트의 DEV 환경을 선택해 **가입·계정 복구**에서 이메일 인증·비밀번호 재설정을 켠다. 가입 검수 시 일반 회원가입도 허용한다.
2. 해당 환경의 `issuer`에 `/account/`를 붙인 주소 또는 연동 서비스의 로그인 화면을 연다. 테스트 계정으로 가입·로그인하면 인증 메일이 생성된다.
3. 관리자 화면의 **개발용 이메일 수신함 → 메일 새로고침**에서 제목을 열고 본문의 인증 주소를 새 탭에 붙여넣는다.
4. 비밀번호 찾기로 다시 메일을 받아 새 비밀번호를 지정한다. 최소 길이 거부, 이전 비밀번호 로그인 거부, 새 비밀번호 로그인 성공, 사용한 링크 재사용 거부를 확인한다.

제목·본문은 Keycloak의 이메일 템플릿과 언어 설정을 따른다. 화면은 텍스트로만 출력한다. 링크는 테스트 계정에 접근할 수 있으므로 공유하지 않는다. 수신함은 최근 1시간·최대 100건이며, 본문에서 안내한 링크 만료 시간과 메일 보존 시간은 서로 다르다.

## 내부 계약과 보호

외부 Nginx는 `/internal/`을 전달하지 않는다. 내부 메서드는 `X-Platform-Mail-Key`를 검증하며 일반 API 키나 사용자 JWT로 호출하지 않는다. 개발 수신함의 외부 경로는 프로젝트 서비스의 기존 관리자 JWT 검증을 거친다. 서비스별 DB 소유권을 유지한다.

| 내부 경로 | 계약 |
|---|---|
| 프로젝트 `GET /internal/v1/email/environments/{UUID}` | 등록된 realm·kind·배포 mode·ACTIVE/READY 여부 반환 |
| 알림 `GET /internal/v1/email/readiness` | 배포 mode와 ready 불리언 |
| 알림 `POST /internal/v1/email` | `{id,environmentId,realm,recipient,subject,textBody,htmlBody}`. 한 명 수신, 접수 상태 반환 |
| 알림 `GET /internal/v1/email/inbox/{UUID}` | dev 배포 전용 모의 메일 목록. 외부 접근은 관리자 API를 사용 |

송신 SPI는 활성 realm·환경 소유 표식·규칙에 맞는 realm 이름을 확인한다. 알림 서비스는 프로젝트 API에서 실제 소속·배포 모드·프로젝트 상태를 다시 확인한다. 송신자가 보낸 환경 종류만 신뢰하지 않는다. 수신 주소 320자, 제목 998자, 텍스트 100,000자, HTML 200,000자로 제한하고 주소·제목의 제어 문자를 거부한다. 프로젝트·알림 호출에 시간 제한을 두며 오류 응답·로그에 본문과 링크를 출력하지 않는다.

## 전달 상태·중복·보존

알림 DB의 `identity_email`은 요청 ID·환경·본문 지문·경로·상태·시각을 저장한다. 운영 메일은 수신자·제목·본문을 저장하지 않으며, NCP 접수 ID만 보관한다. 모의 메일 본문·수신자·제목은 1시간 후 1분 주기의 정리 작업에서 제거하고 메타데이터는 30일 후 제거한다. 중복 방지 범위도 이 30일이다. 플랫폼 관리자 감사 이력의 보존 정책과는 별개다.

- `MOCK`: 외부 발송 없는 개발 메일.
- `SENDING`: NCP 호출 전에 접수를 DB에 커밋한 상태.
- `ACCEPTED`: NCP가 한 건의 요청 ID를 반환한 상태. 실제 배달 완료를 뜻하지 않는다.
- `FAILED`: NCP의 명시적인 4xx 거부.
- `UNKNOWN`: 타임아웃·5xx·응답 불일치 또는 2분 넘게 완료 기록이 없는 `SENDING`. 자동 재발송하지 않는다.

동일 ID·동일 본문 재요청은 저장된 결과를 사용한다. 본문이 바뀌면 409, 진행 중·실패·불명확 결과는 503이며 NCP를 다시 호출하지 않는다. 새 사용자 동작은 새 ID를 만든다. 환경별 최근 1분 최대 20건을 트랜잭션 잠금으로 제한하며 초과는 429다. 불명확한 요청은 NCP 콘솔에서 확인한 뒤 필요하면 사용자가 새 복구 메일을 요청한다. 외부 제공자와 DB는 분산 트랜잭션이 아니므로 완전한 exactly-once 전달이나 동시에 시작한 프로젝트 중지의 외부 호출 취소를 보장하지 않는다.

일반 업무 알림용 큐·자동 재시도·메일별 전달 상태 화면·제공자 결과 동기화는 후속 구현이다. 현재 인증 메일은 Keycloak 요청 안에서 동기 접수하며, 반환 성공은 `MOCK` 또는 `ACCEPTED`다. 오래된 인증 링크를 지연 발송하는 큐를 추가하지 않는다.

## 자동 검증

```sh
docker compose -f compose.yml -f compose.dev.yml --profile test run --rm project-check python /checks/check-email.py
docker compose -f compose.yml -f compose.dev.yml --profile test run --rm project-check python /checks/check-authentication-policy.py
docker compose -f compose.yml -f compose.dev.yml --profile test run --rm smoke
docker compose -f compose.yml -f compose.dev.yml --profile test run --rm --entrypoint sh db-check /checks/check-email-retention.sh
```

`check-email.py`는 새 프로젝트만 만들고 종료 시 중지한다. 브라우저 검증에 쓸 때만 무시되는 로컬 마운트에 `--fixture` 경로를 지정해 성공한 환경을 유지할 수 있다. 이 파일에는 테스트 비밀번호가 있으므로 Git에 넣지 않고, 종료 후 `--cleanup`으로 해당 프로젝트를 중지한다. NCP 실제 이메일은 이 검사에서 발송하지 않는다.

공식 계약: [Keycloak EmailSenderProvider](https://github.com/keycloak/keycloak/blob/26.7.4/server-spi-private/src/main/java/org/keycloak/email/EmailSenderProvider.java), [NCP 메일 접수 API](https://api.ncloud-docs.com/docs/ai-application-service-cloudoutboundmailer-createmailrequest). Keycloak 내부 SPI이므로 버전 변경 시 실제 인증·복구 검사를 다시 수행한다.
