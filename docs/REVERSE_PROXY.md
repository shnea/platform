# NPM·NAS와 접속 IP

다른 프로젝트로 가져갈 공통 원칙·설정 예시·검증 절차와 복사용 AGENTS.md 지침은 [실제 접속 IP 공통 지침](CLIENT_IP_GUIDE.md)에 별도로 정리했다. 이 문서는 현재 플랫폼 환경의 적용 기록이다.

## 현재 적용 결과

2026-09-26 NAS·NPM·플랫폼 전달 설정을 수정해 새 로그인에 접속 IP가 기록되는 것을 확인했다. 사용자는 Wi-Fi를 끈 휴대폰의 LTE/5G 접속에서도 통신사 공인 IP 표시를 확인했다. 기존 Keycloak 세션의 IP는 소급 변경되지 않는다.

| 경로 | 수정 전 | 수정 후 |
| --- | --- | --- |
| 개발 PC → NAS 5443 직접 연결 | NPM이 172.18.0.1 전달 | 실제 PC 주소 192.168.0.55 전달 |
| 내부망 PC → 공개 도메인 | NPM이 172.18.0.1 전달 | 공유기를 돌아 들어오는 경로의 192.168.0.1 전달 |
| 휴대폰 LTE/5G → 공개 도메인 | 플랫폼이 172.20.0.1로 덮어씀 | 새 로그인에서 통신사 공인 IP 표시 확인 |
| 가짜 X-Real-IP 요청 | 호출자가 보낸 가짜 주소 수용 | 가짜 주소 무시 |

개발 연결은 NAS `192.168.0.93`의 Nginx Proxy Manager(NPM) → 개발 PC `192.168.0.55:30140` → 플랫폼 Nginx → Keycloak이다. 운영 시 플랫폼도 NAS로 이동하고 30140은 유지할 예정이다. Cloudflare는 DNS 전용이므로 `CF-Connecting-IP`를 신뢰하지 않는다. [Cloudflare 설명](https://developers.cloudflare.com/dns/proxy-status/)

사용자 환경은 헤놀로지·Container Manager이며 NPM Compose의 게시 포트는 `580:80`, `581:81`, `5443:443`이다. 컨테이너 `npm`은 `npm_default` 네트워크의 `172.18.0.2`, 게이트웨이는 `172.18.0.1`이다. SSH는 9022이며 에이전트 키 인증이 없어 NAS 명령은 사용자가 실행했다. NPM Compose·데이터/인증서 볼륨·다른 컨테이너 포트·공유기 설정은 변경하지 않았다. 5443을 공유기에서 새로 개방할 필요도 없다.

## 수정한 세 구간

### NAS의 기존 NPM 전달 규칙 연결

NAT의 `DOCKER` 체인에는 5443→`172.18.0.2:443` DNAT가 있었지만 `PREROUTING`에서 그 체인으로 연결하는 규칙이 없었다. 처음에는 개발 PC 한 대에만 연결해 직접 경로의 실제 IP 복원을 확인했고, 이후 NAS의 TCP 5443 전체로 적용했다. 모든 포트에 대한 전역 연결은 추가하지 않았다. [Docker NAT 체인 설명](https://docs.docker.com/engine/network/firewall-iptables/)

현재 적용된 명령은 다음과 같다. 이미 있으면 중복 추가하지 않는다.

```sh
sudo iptables -t nat -C PREROUTING -d 192.168.0.93/32 -p tcp --dport 5443 -j DOCKER 2>/dev/null ||
sudo iptables -t nat -I PREROUTING 1 -d 192.168.0.93/32 -p tcp --dport 5443 -j DOCKER
```

NPM HTTPS를 사용하는 다른 도메인의 접속 IP에도 영향을 줄 수 있음을 안내하고 사용자가 적용했다. 초기 PC 한정 검사 규칙은 같은 포트 규칙에 포함되므로 사용자가 아래 명령으로 정리했다. 이후 제공한 조회 출력에는 `DEFAULT_PREROUTING` 이름으로 목적지 `192.168.0.93:5443`의 DOCKER 연결 규칙 하나만 남아 있었다. 정리 후 공개·직접 경로 6건을 재검사해 실제 주소 유지·가짜 헤더 무시와 Nginx 설정 복구를 확인했다.

```sh
sudo iptables -t nat -D PREROUTING -s 192.168.0.55/32 -d 192.168.0.93/32 -p tcp --dport 5443 -j DOCKER
```

### NPM에서 외부 IP 헤더 위조 거부

기본 사설망 신뢰 설정에서는 가짜 X-Real-IP를 보내면 그 값이 접속 주소처럼 전달됐다. `platform.shnea.kr`의 Advanced에 다음을 저장해 해당 호스트의 신뢰 목록을 제한했다. SSL 옵션과 다른 Proxy Host의 설정은 유지했다. 서버 수준 목록은 상위 목록을 그대로 상속하지 않는다. [Nginx 설정 병합 구현](https://github.com/nginx/nginx/blob/master/src/http/modules/ngx_http_realip_module.c)

```nginx
set_real_ip_from 127.0.0.1;
```

### 플랫폼에서 검증된 접속 IP 전달

실제 `.env`와 실행 중인 Nginx에 `NGINX_TRUSTED_PROXY=172.20.0.1`을 적용했다. 현재 Docker Desktop에서 관찰하는 직전 연결 상대 주소이며, 개발 PC의 30140 접근은 NAS로 제한했다. 플랫폼은 신뢰한 상대의 X-Forwarded-For에서 마지막 주소 하나를 선택해 Keycloak에 전달한다. 운영 NAS로 옮길 때는 연결 상대 주소와 포트 접근 제한을 다시 확인한다.

검증은 각 단계마다 새 TCP 연결로 공개·직접 경로의 기본 요청/가짜 X-Forwarded-For/가짜 X-Real-IP, 총 6건을 비교했다. `output/playwright/probe-nas-ip.py`는 인증 정보 없이 health 요청만 잠시 기록하고 원래 Nginx 설정을 복구한다. 최종 도메인 브라우저의 새 로그인·가짜 IP 무시·검사 세션 로그아웃, smoke 11항목, `nginx -t`, 개발 스택 7개 healthy를 확인했다. 실제 외부 모바일 IP 표시는 사용자 확인이며 원문 IP는 문서에 보관하지 않는다.

## 재부팅 후 복원

현재 NAS 규칙은 실행 중인 커널에 적용한 상태다. DSM 작업 스케줄러의 **생성 → 트리거된 작업 → 사용자 정의 스크립트**에 이름 `NPM HTTPS IP Restore`, 사용자 `root`, 이벤트 **부팅**으로 아래 내용을 등록하도록 안내했고, 사용자가 등록·수동 실행 완료를 확인했다. 스크립트의 셸 문법 검사는 통과했으며 실제 NAS 재부팅 검사는 수행하지 않았다. [DSM 작업 스케줄러](https://kb.synology.com/index.php/en-ro/DSM/help/DSM/AdminCenter/system_taskscheduler?version=7)

```sh
for attempt in $(seq 1 60); do
    if iptables -t nat -S DOCKER >/dev/null 2>&1; then
        iptables -t nat -C PREROUTING -d 192.168.0.93/32 -p tcp --dport 5443 -j DOCKER 2>/dev/null ||
        iptables -t nat -I PREROUTING 1 -d 192.168.0.93/32 -p tcp --dport 5443 -j DOCKER
        exit $?
    fi
    sleep 5
done
exit 1
```

Docker NAT 체인을 최대 5분 기다린다. 준비되지 않거나 규칙 추가가 실패하면 실패 상태로 종료한다. NAS 부팅과 별개로 Container Manager 재시작·업데이트가 규칙을 초기화했다면 이 작업을 수동 실행하고 다시 검증한다.

되돌릴 때는 먼저 부팅 작업을 비활성화하고 플랫폼의 신뢰 설정을 루프백으로 돌린 뒤 아래 NAS 규칙을 제거한다. 초기 PC 한정 규칙도 남아 있다면 위 제거 명령을 함께 사용한다. NPM의 위조 방지 설정은 복원된 원본 주소와 별개의 조치다.

```sh
sudo iptables -t nat -D PREROUTING -d 192.168.0.93/32 -p tcp --dport 5443 -j DOCKER
```

## 플랫폼에서 신뢰할 프록시 설정

`NGINX_TRUSTED_PROXY`에는 플랫폼 Nginx가 실제 연결 상대에서 관찰하는 IP 또는 최소 CIDR 하나를 지정한다. 기본값은 루프백이며 외부 프록시를 신뢰하지 않는다. NAS의 LAN 주소와 Docker에서 관찰하는 주소는 다를 수 있다. 전체 인터넷이나 Docker 사설망 전체를 무조건 신뢰하지 않는다.

Nginx의 `real_ip_header X-Forwarded-For`와 `real_ip_recursive off`는 지정한 프록시에서 왔을 때만 목록의 마지막 IP를 선택한다. 앞쪽의 호출자 입력을 사용하지 않는다. NPM이 자신의 올바른 연결 주소를 마지막에 추가하거나 덮어써야 한다. [Nginx real IP 모듈](https://nginx.org/en/docs/http/ngx_http_realip_module.html), [NPM 전달 설정](https://github.com/NginxProxyManager/nginx-proxy-manager/blob/develop/docker/rootfs/etc/nginx/conf.d/include/proxy.conf)

Keycloak으로 전달할 때는 복원한 주소 하나로 X-Forwarded-For를 덮어쓴다. TLS scheme은 계속 `KEYCLOAK_PUBLIC_URL`에서 결정하고, 외부 Forwarded·X-Forwarded-Port는 제거한다. Keycloak 포트는 외부에 공개하지 않는다. [Keycloak 프록시 안내](https://www.keycloak.org/server/reverseproxy)

적용 순서:

1. 가장 바깥쪽 프록시가 진짜 접속 IP를 보고, 외부의 가짜 IP 헤더로 그 값이 바뀌지 않는지 확인한다. DSM을 거친다면 DSM에서 헤더를 덮어쓰고 NPM은 그 프록시 주소만 신뢰해야 한다.
2. 플랫폼의 30140 접근을 직전 프록시로 제한한다. Docker Desktop이 모든 외부 연결을 하나의 게이트웨이로 바꾸는 환경은 호스트 방화벽 제한이 필수다.
3. 상위 구간 검증 후 `.env`의 `NGINX_TRUSTED_PROXY`를 실제 연결 상대 주소로 바꾼다. 현재 개발 값은 `172.20.0.1`이며 검증 후 활성화했다. 운영 NAS에서는 다시 관찰한다.
4. `docker compose -f compose.yml up -d --no-deps nginx`로 재생성하고 `docker compose exec nginx nginx -t`로 확인한다.
5. 새 로그인에서 IP를 확인하고 가짜 X-Forwarded-For·X-Real-IP를 각각 보낸 요청에서도 주소가 변하지 않는지 검사한다. 이미 생성된 Keycloak 세션의 IP는 소급 수정되지 않는다.

## 개발 PC의 포트 제한

현재 Windows의 활성 네트워크는 Public이고 해당 방화벽이 켜져 있다. 이번에 아래 두 규칙만 추가했다. 다른 Docker 포트·기존 규칙·방화벽 프로필 설정은 변경하지 않았다.

- `Platform30140AllowNpm`: TCP 30140의 NAS `192.168.0.93` 접근 허용.
- `Platform30140BlockOtherSources`: TCP 30140의 나머지 IPv4 범위와 전체 IPv6 접근 차단. 기존 Docker 허용 규칙보다 차단이 우선한다.

규칙 확인과 NPM 경유 HTTPS 200은 통과했다. 다른 LAN 기기에서의 실제 차단 검사는 별도로 남아 있다. 현재 꺼져 있는 Private 프로필로 네트워크를 변경하면 이 제한이 적용되지 않으므로 프록시 신뢰 활성화 전에 해당 프로필도 확인한다. 운영 NAS에서는 Docker 포트에 실제 적용되는 방화벽·네트워크 경계를 다시 검증한다.

규칙 확인:

```powershell
Get-NetFirewallRule -Name Platform30140AllowNpm,Platform30140BlockOtherSources
Get-NetFirewallRule -Name Platform30140BlockOtherSources | Get-NetFirewallAddressFilter
Get-NetConnectionProfile
Get-NetFirewallProfile
```

제한을 되돌려야 한다면 먼저 `NGINX_TRUSTED_PROXY=127.0.0.1`로 재생성한 뒤 관리자 PowerShell에서 이번 두 규칙만 제거한다.

```powershell
Remove-NetFirewallRule -Name Platform30140AllowNpm,Platform30140BlockOtherSources
```

## 격리 회귀 검사

프로젝트 루트의 PowerShell에서 빌드한 이미지 태그로 실행한다. 호스트 포트를 열거나 실제 계정을 사용하지 않는다. 원본 Nginx 설정의 Keycloak 목적지만 컨테이너 내부 확인 서버로 바꿔 검증한다.

```powershell
docker run --rm --entrypoint sh --mount "type=bind,source=$($PWD.Path)/scripts,target=/checks,readonly" registry.shnea.kr/platform-nginx:0.1.0-dev /checks/check-proxy-ip.sh
```

일반 인증·공통 소셜 콜백 두 경로에서 신뢰/비신뢰 연결 상대, IPv4/IPv6, 누락/잘못된 IP, 가짜 주소 목록과 TLS 관련 헤더를 검사한다. 이 검사는 플랫폼 Nginx 동작 검증이며 NAS·NPM의 올바른 설정을 대신하지 않는다.
