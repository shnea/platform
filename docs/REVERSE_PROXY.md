# NPM·NAS와 접속 IP

## 현재 연결과 남은 확인

개발 연결은 NAS `192.168.0.93`의 Nginx Proxy Manager(NPM) → 개발 PC `192.168.0.55:30140` → 플랫폼 Nginx → Keycloak이다. 운영 시 플랫폼도 NAS로 이동하고 30140 포트는 유지할 예정이다. 사용자는 30140의 외부 포트포워딩이 없다고 확인했다.

사용자가 제공한 NPM Compose는 `jc21/nginx-proxy-manager:latest`, 컨테이너 이름 `npm`, NAS 포트 `580:80`, `581:81`, `5443:443`이다. 별도 네트워크 모드는 지정하지 않았고 해당 Proxy Host의 Advanced 입력칸은 비어 있다. 이미지의 실제 실행 버전은 `latest` 표기만으로 확인할 수 없다. 기존 데이터·인증서 볼륨과 NPM 설정은 변경하지 않는다.

Cloudflare는 DNS 전용이다. 이 모드에서는 HTTP 요청이 Cloudflare 프록시를 통과하지 않으므로 `CF-Connecting-IP`를 신뢰하지 않는다. 도메인 등록 기관·인증서 발급 방식과 접속 IP 전달은 별개다. [Cloudflare 설명](https://developers.cloudflare.com/dns/proxy-status/)

2026-09-26 개발 PC에서 공개 도메인으로 보낸 health 요청을 플랫폼 Nginx에서 임시 관찰했다. 인증 정보나 요청 URL은 기록하지 않았고 진단 설정은 제거했다.

| 요청 | Nginx 연결 상대 | 전달된 X-Forwarded-For | 전달된 X-Real-IP |
| --- | --- | --- | --- |
| 별도 IP 헤더 없음 | 172.20.0.1 | 172.18.0.1 | 172.18.0.1 |
| 가짜 X-Forwarded-For | 172.20.0.1 | 198.51.100.99, 172.18.0.1 | 172.18.0.1 |
| 가짜 X-Real-IP | 172.20.0.1 | 203.0.113.99 | 203.0.113.99 |

플랫폼 앞 구간에서 이미 원래 주소가 사라지며, 호출자가 보낸 X-Real-IP가 실제 연결 주소처럼 전달된다. 따라서 현재는 `NGINX_TRUSTED_PROXY=127.0.0.1`을 유지한다. 임시 신뢰 활성화 상태의 실제 로그인에서도 가짜 주소가 기록되는 것을 확인해 즉시 비활성화했고 검사 세션을 종료했다. 실제 IP 복원 완료로 간주하지 않는다.

NPM의 [현재 기본 설정](https://github.com/NginxProxyManager/nginx-proxy-manager/blob/develop/docker/rootfs/etc/nginx/nginx.conf)은 사설망을 신뢰하고 X-Real-IP를 읽는다. 위 결과와 일치하지만 설치된 NPM의 버전·실제 전체 설정은 아직 확인하지 않았다. Advanced가 비어 있어도 기본 설정은 적용된다. 공유기 외부 443의 **내부 대상 IP:포트**와 DSM 역방향 프록시 사용 여부도 아직 미확인이다. NAS의 NAT가 주소를 지우는 경우에는 마지막 플랫폼 설정만으로 원래 주소를 되살릴 수 없다. 내부망의 도메인 재접속과 LTE/5G 외부 접속도 구분해서 확인한다.

추가로 개발 PC에서 `curl.exe --resolve platform.shnea.kr:5443:192.168.0.93 https://platform.shnea.kr:5443/healthz`로 공개 DNS·공유기 경로를 거치지 않고 NAS의 NPM 게시 포트에 직접 요청했다. TLS 검증을 유지한 HTTPS 200이었고, 일반 요청·가짜 X-Forwarded-For·가짜 X-Real-IP 세 경우의 전달 결과가 위 공개 도메인 검사와 각각 같았다. 총 6건의 health 요청 뒤 임시 진단 설정을 복구하고 `nginx -t`를 통과했다.

따라서 NAS의 게시 포트→NPM 구간만으로도 주소 손실과 가짜 X-Real-IP 수용이 재현된다. Docker의 사용자 공간 포트 프록시 또는 NAT 경로를 확인해야 하며, 정확한 NAS 내부 원인은 아직 미확정이다. 공유기 443 규칙을 새로 만드는 것으로 해결된다고 판단하지 않는다. NPM의 호스트 네트워크 전환도 게시 포트 매핑을 무시하므로 DSM의 80/443과 충돌할 수 있다. [Docker 호스트 네트워크 설명](https://docs.docker.com/engine/network/drivers/host/)

다음은 NAS의 실행 중 Docker 네트워크와 포트 프록시, `userland-proxy`·`iptables` 설정을 읽기 전용으로 확인하는 단계다. NAS에서 실행 가능한 경로가 확인되기 전에는 Docker 전체 설정 변경·재시작을 수행하지 않는다. 현재 플랫폼의 외부 IP 헤더 신뢰는 계속 비활성화한다.

## 플랫폼에서 신뢰할 프록시 설정

`NGINX_TRUSTED_PROXY`에는 플랫폼 Nginx가 실제 연결 상대에서 관찰하는 IP 또는 최소 CIDR 하나를 지정한다. 기본값은 루프백이며 외부 프록시를 신뢰하지 않는다. NAS의 LAN 주소와 Docker에서 관찰하는 주소는 다를 수 있다. 전체 인터넷이나 Docker 사설망 전체를 무조건 신뢰하지 않는다.

Nginx의 `real_ip_header X-Forwarded-For`와 `real_ip_recursive off`는 지정한 프록시에서 왔을 때만 목록의 마지막 IP를 선택한다. 앞쪽의 호출자 입력을 사용하지 않는다. NPM이 자신의 올바른 연결 주소를 마지막에 추가하거나 덮어써야 한다. [Nginx real IP 모듈](https://nginx.org/en/docs/http/ngx_http_realip_module.html), [NPM 전달 설정](https://github.com/NginxProxyManager/nginx-proxy-manager/blob/develop/docker/rootfs/etc/nginx/conf.d/include/proxy.conf)

Keycloak으로 전달할 때는 복원한 주소 하나로 X-Forwarded-For를 덮어쓴다. TLS scheme은 계속 `KEYCLOAK_PUBLIC_URL`에서 결정하고, 외부 Forwarded·X-Forwarded-Port는 제거한다. Keycloak 포트는 외부에 공개하지 않는다. [Keycloak 프록시 안내](https://www.keycloak.org/server/reverseproxy)

적용 순서:

1. 가장 바깥쪽 프록시가 진짜 접속 IP를 보고, 외부의 가짜 IP 헤더로 그 값이 바뀌지 않는지 확인한다. DSM을 거친다면 DSM에서 헤더를 덮어쓰고 NPM은 그 프록시 주소만 신뢰해야 한다.
2. 플랫폼의 30140 접근을 직전 프록시로 제한한다. Docker Desktop이 모든 외부 연결을 하나의 게이트웨이로 바꾸는 환경은 호스트 방화벽 제한이 필수다.
3. 상위 구간 검증 후 `.env`의 `NGINX_TRUSTED_PROXY`를 실제 연결 상대 주소로 바꾼다. 현재 개발 값의 후보는 `172.20.0.1`이며 아직 활성화하지 않는다. 운영 NAS에서는 다시 관찰한다.
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
docker run --rm --entrypoint sh --mount "type=bind,source=$($PWD.Path)/scripts,target=/checks,readonly" register.shnea.kr/platform-nginx:0.1.0-dev /checks/check-proxy-ip.sh
```

일반 인증·공통 소셜 콜백 두 경로에서 신뢰/비신뢰 연결 상대, IPv4/IPv6, 누락/잘못된 IP, 가짜 주소 목록과 TLS 관련 헤더를 검사한다. 이 검사는 플랫폼 Nginx 동작 검증이며 NAS·NPM의 올바른 설정을 대신하지 않는다.
