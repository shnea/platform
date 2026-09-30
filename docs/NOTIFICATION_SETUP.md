# NCP 알림 설정과 실제 연결 검사

NCP 키·서비스 ID는 로컬 `.env.dev` / `.env.prod`에서 알림 컨테이너로 전달한다. Keycloak 인증·복구 메일의 NCP 전달 어댑터와 개발 모의 수신함을 구현했다. [인증 이메일](IDENTITY_EMAIL.md)에 모드 구분·설치·검수·저장 상태를 설명한다. 일반 알림 업무 API·6채널 재시도·전체 이력 UI는 미완료다. 아래 도구의 이전 단일 발송 성공은 새 어댑터의 실제 외부 수신 검수와 구분한다.

## 설정

| 변수 | 용도 |
|---|---|
| `PLATFORM_MAIL_SECRET` | Keycloak·프로젝트·알림 서비스 사이의 인증 이메일 전용 비밀값. Dotenvx 암호화 환경파일에서 관리 |
| `NCP_ACCESS_KEY`, `NCP_SECRET_KEY` | API Gateway 서명에 사용하는 IAM 키. 해당 Mailer·SENS 권한 필요 |
| `NCP_MAIL_API_ENDPOINT`, `NCP_MAIL_API_PATH` | `https://mail.apigw.ntruss.com`, `/api/v1/mails` |
| `NCP_MAIL_SENDER_ADDRESS`, `NCP_MAIL_SENDER_NAME` | 발신 이메일·표시 이름 |
| `NCP_SMS_SERVICE_ID`, `NCP_SMS_SENDER_NUMBER` | SMS 서비스 ID·NCP에 등록한 발신번호 |
| `NCP_BIZ_MESSAGE_SERVICE_ID`, `NCP_KAKAO_CHANNEL_ID` | 카카오 메시지 서비스 ID·채널. 메시지 종류와 승인 템플릿은 추가 확인 |
| `NCP_TEST_EMAIL`, `NCP_TEST_PHONE` | 운영자 검사 전용 수신처. 전화번호는 숫자만 입력 |

기존 `MAIL_API_ACCESS_KEY`·`MAIL_API_SECRET_KEY`를 공통 NCP IAM 변수로 대응했다. 메일 설정에 있던 키로 SMS도 호출할 수 있는지는 IAM 권한에 달려 있으며 이번 실제 검수에서 두 채널을 모두 확인했다. 키·전화번호·수신 이메일은 Git에 넣지 않는다.

## 실제 발송 검사

외부 발송은 명시적인 `--send`가 있을 때만 1건 수행한다. 앱의 DEV Mock 경로와 분리된 운영자 도구이며 일반 테스트·Compose 기동만으로 발송하지 않는다. 고정된 NCP HTTPS 호스트만 호출하고 리다이렉트·자동 재발송을 하지 않는다. 타임아웃 시 접수 여부가 불명확하므로 콘솔 확인 없이 다시 보내지 않는다.

```sh
./scripts/dev.ps1 --profile test run --rm --no-deps notification-check python /checks/check-ncp-live.py email --send
./scripts/dev.ps1 --profile test run --rm --no-deps notification-check python /checks/check-ncp-live.py sms --send
```

응답의 `requestId`로 발송 결과를 조회한다. `--status`는 재발송하지 않는다.

```sh
./scripts/dev.ps1 --profile test run --rm --no-deps notification-check python /checks/check-ncp-live.py email --status REQUEST_ID
./scripts/dev.ps1 --profile test run --rm --no-deps notification-check python /checks/check-ncp-live.py sms --status REQUEST_ID
```

2026-09-26 검수: 사용자가 지정한 수신처로 이메일·SMS 각 1건 발송. 이메일은 `allSentSuccess=true`, `sentCount=1`, SMS는 `COMPLETED`, `statusCode=0`, `statusName=success` 확인. 이는 제공자 발송 결과이며 사용자의 실제 메일함·단말 확인과 구분한다. 카카오 메시지는 채널·템플릿 미제공으로 미검증이다. 사용자가 채널을 아직 만들지 않았고, 준비 후 별도로 요청하기로 했으므로 실제 발송 검수는 그때 진행한다.

공식 규격: [메일 발송](https://api.ncloud-docs.com/docs/ai-application-service-cloudoutboundmailer-createmailrequest), [메일 결과](https://api.ncloud-docs.com/docs/ai-application-service-cloudoutboundmailer-getmailrequeststatus), [SMS 발송](https://api.ncloud-docs.com/docs/sens-sms-send), [SMS 결과](https://api.ncloud-docs.com/docs/sens-sms-get).
