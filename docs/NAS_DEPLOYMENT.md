# 개발·릴리스·NAS 배포

개발 PC가 이미지를 만들고 게시하며, NAS는 지정된 이미지를 받아 실행한다.

| 작업 | 명령 | 환경 |
| --- | --- | --- |
| 개발 | `./scripts/dev.ps1` | 암호화된 `.env.dev` |
| 빌드·게시 | `./scripts/release.ps1` | 운영 환경·복호화 키 불필요 |
| 운영 갱신 | `sudo sh scripts/deploy.sh <SHA 12자리>` | 암호화된 `.env.prod` |

## 최초 도구·키 준비

- 개발 PC: Docker와 Compose **2.24.4 이상**, Windows PowerShell 또는 PowerShell 7. macOS에서는 PowerShell 7로 같은 `.ps1`을 실행한다. NAS의 운영 구성은 Compose **2.20 이상**을 사용한다. NAS에서 개발 Compose를 사용하지 않는다.
- Dotenvx **2.24.0**: [공식 릴리스](https://github.com/dotenvx/dotenvx/releases/tag/v2.24.0)의 OS·CPU에 맞는 파일을 받고 checksums.txt의 SHA-256과 비교한다. 개발 PC의 `.tools/dotenvx.exe`(Windows) 또는 `.tools/dotenvx`(macOS), NAS의 `.tools/dotenvx`에 둔다. PATH 설치도 가능하다. 배포마다 자동 설치하지 않는다.
- 개발 키는 별도 보안 경로로 받은 `.env.keys`에 둔다. NAS에는 `DOTENV_PRIVATE_KEY_PROD` **한 개만** 있는 `.secrets/prod.keys`를 별도로 제공한다. 전체 개발 키 파일을 운영에 복사하지 않는다. 대안으로 실행 환경에 운영 키를 제공할 수 있다.
- NAS 파일 키는 `chmod 600 .secrets/prod.keys`, 바이너리는 `chmod 700 .tools/dotenvx`로 제한한다. SMB 접근 권한도 운영자에게만 허용한다. 키와 백업은 Git·배포 ZIP에서 제외한다.
- 레지스트리 인증은 `docker login registry.shnea.kr`로 각 실행 계정에 준비한다. NAS에서 sudo를 사용한다면 sudo 실행 계정도 Pull 권한이 필요하다. 인증정보는 파일 예제나 명령 인자에 적지 않는다.

`.env.dev`와 `.env.prod`는 암호문 상태로 Git에 포함된다. Compose에 `--env-file .env.prod`를 직접 전달하지 않는다. 스크립트가 먼저 복호화해 필요한 환경변수를 주입한다. 이전 `.env`나 `COMPOSE_FILE`에 의존하지 않는다.

### 설정 변경

기존 환경은 `.env.example`의 변수 설명을 참고해 Dotenvx로 필요한 항목만 수정한다. Dotenvx `set`의 암호화 기능을 사용하며 비밀값을 셸 이력·채팅·검증 로그에 남기지 않는다. 수정 후 암호문인지 확인하고 키를 따로 보관한다. 기존 DB 비밀번호는 환경파일 수정만으로 바뀌지 않으므로 DB 계정 변경과 함께 계획해야 한다.

`python scripts/init-env.py --environment dev` 또는 `--environment prod`는 **환경파일이 없는 신규 설치 전용**이다. 기존 파일을 덮어쓰거나 이미 운영 중인 비밀번호를 재생성하지 않는다. Git에서 받은 기존 환경은 해당 키를 받아 사용한다.

## 개발

```powershell
./scripts/dev.ps1
./scripts/dev.ps1 ps -a
./scripts/dev.ps1 logs --tail 80 project-service
./scripts/dev.ps1 --profile test run --rm smoke
```

개발은 `compose.yml + compose.dev.yml`을 사용한다. 기존 `shnea-platform-dev` 프로젝트와 named volume을 유지하고 운영 데이터 bind mount와 고정 네트워크 대역을 제거한다. 개발 태그 `dev`는 운영 배포 태그가 아니다. 개발 주소는 `.env.dev`의 `PLATFORM_WEB_URL`과 바인딩 설정을 따른다.

격리 DB·콜백·로그 검증은 `compose.test.yml`의 `jobs / callbacks / logs` 프로필을 사용한다. 운영 Compose와 병합하지 않는다. 예:

```sh
docker compose --env-file .env.example -p platform-job-checks -f compose.test.yml --profile jobs up --abort-on-container-exit --exit-code-from job-check
docker compose --env-file .env.example -p platform-job-checks -f compose.test.yml --profile jobs down
```

## 릴리스

변경을 검증하고 커밋한 뒤 개발 PC에서 실행한다.

```powershell
./scripts/release.ps1 -Plan
./scripts/release.ps1
```

자체 이미지 8개를 전체 Git SHA 앞 **정확히 12자리**로 빌드하고 게시한다. `REGISTRY_HOST` 또는 `-Registry`로 기본 `registry.shnea.kr`을 변경한다. NAS CPU는 `linux/amd64`이며 필요 시 `-Platform linux/arm64`를 선택한다. 이미지 이름은 기존 `platform-*`를 유지한다. 운영 비밀값은 읽지 않는다.

미커밋 변경이나 이미 게시된 태그가 있으면 중단한다. 동일 커밋 태그를 덮어쓰지 않으며 기반 이미지 갱신도 커밋으로 남긴다. 같은 태그의 release를 동시에 실행하지 않는다. Registry가 지원하면 불변 태그 정책을 활성화한다. 일부 이미지 게시에 실패한 릴리스는 배포하지 않는다. 이 경우 기존 게시물은 보존하고 원인을 고친 새 커밋으로 릴리스한다.

성공 시 `output/releases/<태그>/release.json`에 전체 SHA·이미지별 digest·게시 시각을 남긴다. GitHub Actions도 이 release와 아래 deploy를 사용한다. `main` push 시 GitHub에서 검증·빌드·게시 후 NAS에 SSH 배포한다. 자동화 사용 중 같은 커밋의 수동 release를 중복 실행하지 않는다. [CI/CD 설명서](CICD.md)를 참고한다. Registry 자동 삭제는 별도 작업이다.

## NAS 구성 전달·배포

`NAS_DEPLOY_PATH`는 운영 설정 폴더, `PLATFORM_DATA_ROOT`는 영속 데이터 루트를 뜻한다. 실제 경로는 Git에 적지 않고 운영 설정으로 관리한다. 아래 수동 명령은 NAS 셸에 `NAS_DEPLOY_PATH`를 별도로 지정한 뒤 실행한다. 신규 설치는 암호화된 `.env.prod`의 데이터 루트·바인딩 주소를 해당 서버에 맞게 설정해야 한다.

```sh
python scripts/package-nas.py
```

생성된 `output/releases/platform-nas-config.zip`을 NAS **`${NAS_DEPLOY_PATH}`**에 푼다. ZIP에는 `compose.yml`, 암호화 `.env.prod`, deploy 스크립트, Loki 설정과 운영 문서·`운영안내.html`만 들어 있다. 키·도구·데이터·개발 Compose는 포함하지 않는다. 키와 Dotenvx는 최초 준비 때 별도로 전달한다. CI는 release 기록이 있을 때 생성되는 `.tar.gz`를 전용 SSH 명령으로 전달하며 직접 ZIP을 풀 필요가 없다.

NAS SSH에서 프로젝트 폴더로 이동해 **release에 성공한 실제 태그**를 전달한다.

```sh
cd "$NAS_DEPLOY_PATH"
sudo chmod 644 infra/loki/loki.yml
sudo sh scripts/deploy.sh check <SHA_12>
sudo sh scripts/deploy.sh <SHA_12>
sudo sh scripts/deploy.sh status
```

`check`는 복호화·구성 해석만 검사하며 이미지 존재나 서비스 기동 성공을 뜻하지 않는다. 실제 deploy는 전체 Pull, 이미지 revision 검사, `up -d --no-build --remove-orphans --wait`, Loki readiness 검사 순서다. 복호화·Pull·revision 검사가 실패하면 컨테이너를 변경하지 않는다. `storage-init`·`identity-setup`의 `Exited (0)`은 정상이다. 정상 상태 확인 후에만 `.deploy/current`·`previous`·digest·설정 해시·시각 기록을 갱신한다. 동시에 같은 프로젝트를 배포하지 않는다.

기동 이후 실패하면 일부 컨테이너가 새 이미지로 바뀌었을 수 있다. `.deploy/history`와 실제 컨테이너 상태를 확인한다. 자동으로 데이터나 이미지를 되돌리지 않는다. 비정상 종료로 `.deploy/lock`이 남으면 실행 중인 배포가 없는지 먼저 확인한다.

| 위치 | 저장 내용 |
| --- | --- |
| `${NAS_DEPLOY_PATH}` | 실행 설정·운영 키·도구·배포 기록 |
| `${PLATFORM_DATA_ROOT}/postgres` | DB·Job |
| `${PLATFORM_DATA_ROOT}/files` | 파일·이미지·영상 |
| `${PLATFORM_DATA_ROOT}/loki` | 로그 |

운영 데이터 경로와 권한 준비는 `compose.yml`에 포함된다. 기존 데이터를 초기화하지 않는다. NAS 커널을 위해 운영 `*_CPUS=0`을 유지하고 메모리 제한은 보존한다. 네트워크는 앱 `10.250.10.0/24`, DB `10.250.11.0/24`, 로그 `10.250.12.0/24`이며 DB·로그는 내부 전용이다. NPM 전달 주소는 **HTTP / ${NAS_LAN_ADDRESS} / 30140**, 외부 주소는 **https://platform.shnea.kr**이다. 기존 관리자 비밀번호·MFA·외부 연동 값은 유지한다.

## 롤백·기존 이미지 보호

이전 이미지와 현재 DB 스키마·Compose·설정의 호환성을 확인한 뒤 같은 `deploy.sh <이전 SHA>`를 실행한다. 이미지 롤백은 DB·사용자 파일 복원이 아니다. 비호환 변경은 백업 복구나 별도 수정 배포가 필요하다.

전환 전 실제 운영 태그 `0.1.3`은 `.deploy/legacy-tag`에, 실제 이미지 ID는 `.deploy/legacy-images`에 보호한다. 새 릴리스부터 SHA 정책을 적용한다. 과거 이미지를 임의 SHA로 다시 이름 붙이지 않는다. 보호된 기존 이미지를 사용할 필요가 있으면 `sudo sh scripts/deploy.sh --legacy 0.1.3`으로 같은 검증·배포 절차를 실행한다. 이 옵션은 `.deploy/legacy-tag`에 명시된 태그만 허용한다. 보호한 이미지 ID와 Pull 결과가 다르면 컨테이너 갱신 전에 중단한다.

Registry 정리는 이번 전환에서 실행하지 않는다. 향후 저장소별 최근 최소 5개 **또는** 최근 30일 이미지와 현재·직전 정상·진행 중 배포·명시적 보호 이미지를 모두 유지한다. 배포 기록과 실제 운영 상태를 함께 확인할 수 있어야 자동 삭제를 도입할 수 있다. 오래된 태그라는 이유로 `0.1.3`이나 다른 프로젝트 이미지를 지우지 않는다.
