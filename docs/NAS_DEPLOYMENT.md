# 실행·배포

환경 파일 이름은 그대로 사용한다. 레지스트리는 `registry.shnea.kr`, 배포 태그는 `0.1.0`으로 준비했다.

| 파일 | 용도 |
| --- | --- |
| `.env.dev` | 로컬 개발, 기존 개발 비밀번호 유지 |
| `.env.build` | 이미지 빌드·업로드 |
| `.env.prod` | NAS 운영, 기존 `.env`의 비밀번호·내부 키·외부 연동 설정 반영 |

## 로컬 개발

프로젝트 폴더에서 실행한다.

```sh
docker compose --env-file .env.dev up -d --build
```

## 로컬에서 이미지 업로드

프로젝트 폴더에서 순서대로 실행한다.

```sh
docker compose --env-file .env.build build
docker compose --env-file .env.build push
```

## NAS 배포

준비된 **`output/releases/platform-nas-deploy.zip`** 내용을 NAS **`/volume1/docker/prod/platform`**에 그대로 복사한다. `.env.prod`도 ZIP에 들어 있다. 파일 이름을 바꾸거나 비밀번호를 다시 만들지 않는다.

NAS SSH에서 실행한다.

```sh
cd /volume1/docker/prod/platform
sudo docker-compose --env-file .env.prod pull
sudo docker-compose --env-file .env.prod up -d
```

데이터 폴더와 최초 관리자 설정은 자동으로 준비된다. `storage-init`·`identity-setup`의 `Exited (0)`은 정상이다.

NAS의 기본 Docker 주소 대역 소진을 피하도록 `compose.nas.yml`에 플랫폼 전용 대역을 지정했다: 앱 `10.250.10.0/24`, DB `10.250.11.0/24`, 로그 `10.250.12.0/24`. 기존 NAS 라우팅 표와 겹치지 않으며 DB·로그 네트워크는 내부 전용이다. 이전 파일로 네트워크 생성이 실패했다면 **`compose.nas.yml`만 교체**하고 같은 `up -d` 명령을 다시 실행한다. 이미지 재빌드·재다운로드는 필요 없다.

| 위치 | 저장 내용 |
| --- | --- |
| `/volume1/docker/prod/platform` | 실행 설정 |
| `/volume2/homes/platform/postgres` | DB·Job |
| `/volume2/homes/platform/files` | 파일·이미지·영상 |
| `/volume2/homes/platform/loki` | 로그 |

NPM `platform.shnea.kr`의 전달 주소는 **HTTP / 192.168.0.93 / 30140**으로 설정한다. 접속 후 관리자 `admin`과 `.env.prod`의 `PLATFORM_ADMIN_PASSWORD`로 로그인하고 MFA를 등록한다.

상태 확인:

```sh
sudo docker-compose --env-file .env.prod ps -a
```

내부 키·DB/관리자 비밀번호·NCP·소셜 설정은 기존 `.env`에서 채워 두었다. 직접 옮겨 적을 필요 없다. 빌드용 파일에는 비밀값이 필요 없다.

ZIP과 `.env.prod`에는 비밀번호가 있으므로 Git이나 공개 자료에 올리지 않는다. 기존 운영 데이터가 생긴 뒤에는 `.env.prod`의 비밀번호를 재생성하지 않는다. NAS 실기동·프록시 IP·백업 복원은 NAS에서 검수한다.
