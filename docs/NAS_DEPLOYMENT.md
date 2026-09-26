# NAS 신규 운영 배포

사용자가 직접 실행한다. 개발 DB·파일·로그·실제 `.env`는 복사하지 않는다.

| 경로 | 넣을 것 |
| --- | --- |
| `/volume1/docker/prod/platform` | `compose.yml`, `compose.nas.yml`, `.env.example`, 운영용으로 새로 생성한 `.env` |
| 위 루트의 `infra/loki/loki.yml` | 로그 저장소 설정 |
| 위 루트의 `scripts/` | `init-env.py`, `prepare-nas.sh` |
| `/volume2/homes/platform/postgres` | PostgreSQL 데이터: 프로젝트·인증·파일 메타데이터·알림·Job |
| `/volume2/homes/platform/files` | 업로드 원본·썸네일·미리보기·HLS 변환 결과 |
| `/volume2/homes/platform/loki` | 로그·색인·WAL·삭제 마커 |

## 이미지 먼저 준비 — 개발 PC

태그는 검증한 릴리스마다 새 값으로 정한다. 아래 `0.1.0-jobs-logs.1`은 배포용 예시이며 **레지스트리에 게시됐다는 뜻이 아니다**. 이 저장소의 변경을 확인한 뒤 실행한다.

```powershell
$env:IMAGE_TAG = '0.1.0-jobs-logs.1'
docker login register.shnea.kr
docker compose -f compose.yml -f compose.dev.yml build admin-web db keycloak project-service file-service notification-service nginx identity-setup
docker compose -f compose.yml push admin-web db keycloak project-service file-service notification-service nginx identity-setup
Remove-Item Env:IMAGE_TAG
```

NAS CPU에 맞는 Linux 이미지가 필요하다. NAS에서 `uname -m` 확인: `x86_64`는 `linux/amd64`. 다른 아키텍처라면 해당 대상으로 빌드한다. 개발 실행 스택은 위 명령으로 재생성되지 않는다.

## NAS에 파일 배치·초기 설정

위 표의 배포 파일을 루트에 넣는다. **`compose.dev.yml`은 사용하지 않는다.** SSH 포트는 9022이며 Python 3와 Docker Compose가 필요하다. `docker compose`가 없는 DSM은 설치된 `docker-compose` 명령으로 바꾼다.

개발 PC에서 `python scripts/package-nas.py`를 실행하면 `output/releases/platform-nas-config.zip`에 필요한 설정 파일만 묶인다. 이 ZIP을 NAS 배포 루트에 푼다. 실제 `.env`·데이터·이미지는 들어 있지 않다.

```sh
cd /volume1/docker/prod/platform
python3 scripts/init-env.py --nas --image-tag 0.1.0-jobs-logs.1
chmod 600 .env
sudo docker login register.shnea.kr
sudo docker compose -f compose.yml -f compose.nas.yml --profile setup pull
sudo sh scripts/prepare-nas.sh
```

생성된 `.env`는 운영용 무작위 비밀값을 사용한다. NAS 바인딩 `192.168.0.93:30140`, `PLATFORM_MODE=prod`, 도메인 `https://platform.shnea.kr`가 설정된다. NCP·소셜 연결이 필요하면 해당 값만 별도로 입력한다. 비밀값을 문서·채팅에 붙이지 않는다.

## 실행

```sh
sudo docker compose -f compose.yml -f compose.nas.yml up -d
sudo docker compose -f compose.yml -f compose.nas.yml ps
# Keycloak이 healthy가 된 뒤 최초 관리자/인증 설정
sudo docker compose -f compose.yml -f compose.nas.yml --profile setup run --rm identity-setup
```

NPM `platform.shnea.kr` 전달 주소를 **HTTP / 192.168.0.93 / 30140**으로 바꾼다. wildcard SSL 설정은 유지한다. 30140은 외부 포트포워딩하지 않고 NPM에서만 접근 가능하도록 제한한다. NPM에서 들어오는 실제 직전 프록시 주소를 확인한 뒤 `.env`의 `NGINX_TRUSTED_PROXY`를 해당 주소 하나로 설정하고 nginx를 재생성한다. 개발 PC의 Docker 게이트웨이 주소를 그대로 복사하지 않는다. 상세 기준: [프록시 설정](REVERSE_PROXY.md).

```sh
sudo docker compose -f compose.yml -f compose.nas.yml up -d nginx
curl -fsS http://192.168.0.93:30140/healthz
sudo docker compose -f compose.yml -f compose.nas.yml exec project-service curl -fsS http://loki:3100/ready
```

`https://platform.shnea.kr` → 새 관리자 `admin` / 새 `.env`의 `PLATFORM_ADMIN_PASSWORD`로 로그인 → MFA 등록·복구 코드 보관 → 프로젝트와 PROD 환경 생성 → Job·로그 권한을 선택해 새 서버 키 발급 → 공개 연결 지침의 예제로 검수한다. DEV 데이터가 없는 빈 프로젝트 목록이 정상이다. 기존 개발 관리자 비밀번호·MFA·서버 키는 새 운영 계정에 이전되지 않는다.

## 유지·백업·되돌리기

- 업데이트는 배포 설정·이미지 태그만 교체하고 `up -d`. volume2 데이터 폴더는 삭제하거나 교체하지 않는다. `prepare-nas.sh`는 빈 데이터 디렉터리의 최초 설치 전용이다.
- 변경 전 `.env`·Compose·Loki 설정을 비공개로 백업한다. DB는 PostgreSQL 백업, 파일·로그는 서비스 정지 후 파일시스템 스냅샷/백업을 수행한다. 전체를 일관되게 백업하려면 NPM 접근 중지 → `docker compose ... stop` → volume2 세 디렉터리 스냅샷/백업 → `docker compose ... start` 순서다. 백업은 원본과 다른 저장 장치에도 보관한다.
- 복원 검수는 별도 경로·포트의 격리 스택에서 진행한다. 기존 DB에 이전 이미지로 무조건 되돌리지 않는다. 마이그레이션 호환성을 확인하거나 변경 전 데이터·설정·이미지를 한 묶음으로 복원한다.
- PostgreSQL·Loki 데이터 폴더를 File Station에서 수동 편집하지 않는다. `/volume2/homes`의 DSM 사용자 권한·ACL이 컨테이너 UID 접근을 막으면 해당 디렉터리 권한을 확인한다. 전체 homes의 권한을 변경하지 않는다.
- NAS 실기동·볼륨 ACL·프록시 IP·실제 백업 복원은 NAS에서 별도 검수한다. 이 안내가 해당 검수의 완료를 뜻하지 않는다.
