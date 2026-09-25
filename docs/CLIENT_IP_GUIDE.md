# 다른 프로젝트에서 실제 접속 IP를 기록하는 지침

이 문서는 프로젝트와 별개로 복사해 사용할 수 있는 개발·배포 지침이다. HTTP 역방향 프록시 뒤의 웹 서비스가 대상이며, 예시 주소·도메인·포트는 배포 환경에 맞게 바꾼다. 2026-09-26 작성.

## 1. 기본 원칙

**외부 요청을 처음 받는 프록시가 접속 IP를 확인하고, 내부 서비스는 신뢰한 프록시가 전달한 값만 기록한다.**

```text
이용자 → 공유기/NAT → 외부 프록시(NPM 등) → 프로젝트 Nginx → 애플리케이션
                       접속 IP 확인          검증·전달          기록
```

- 브라우저가 보낸 `X-Forwarded-For`, `X-Real-IP`, `Forwarded`는 그 자체로 신뢰하지 않는다.
- 신뢰할 프록시는 실제 연결 상대의 IP 또는 필요한 최소 범위로 지정한다. 사설망 전체나 모든 IP를 신뢰하지 않는다.
- 애플리케이션과 내부 프록시의 포트는 지정한 앞단 프록시만 접근하도록 제한한다. 외부 포트포워딩이 없더라도 내부망 우회 접근은 별도로 막는다.
- Docker 게이트웨이가 모든 요청의 연결 상대로 보이면 IP 신뢰 설정만으로 출발지를 구분할 수 없다. 호스트 방화벽·전용 네트워크 등에서 접근 경계를 먼저 만든다.
- 앞단에서 이미 원래 IP를 잃었다면 뒤에서 헤더를 읽는 것만으로 복원할 수 없다. 처음 IP가 바뀌는 구간부터 수정한다.
- IP는 접속 경로의 주소다. 통신사 NAT·공유기·VPN을 쓰면 여러 사용자가 공유할 수 있으므로 개인이나 기기의 고유 식별자로 사용하지 않는다.

## 2. 설정 전에 확인할 것

| 확인 항목 | 기록할 내용 |
| --- | --- |
| 외부 진입 경로 | 공유기 → NAS → NPM → 프로젝트 등 전체 순서 |
| TLS 종료 위치 | HTTPS를 해석하는 프록시와 내부 전달 프로토콜 |
| CDN 사용 여부 | Cloudflare DNS 전용인지 프록시 사용인지 |
| 연결 상대 주소 | 각 서버가 실제로 보는 직전 연결 상대 IP |
| 전달 헤더 | 각 구간의 X-Forwarded-For·X-Real-IP 값 |
| 포트 접근 범위 | 프록시를 거치지 않고 내부 포트에 접근 가능한지 |
| 저장 위치 | 접속 로그·로그인 세션·감사 이력 등 필요한 위치 |
| 재시작 유지 | 컨테이너 재생성·호스트 재부팅 후 유지 방법 |

Cloudflare가 DNS 전용이면 HTTP 요청은 Cloudflare 프록시를 통과하지 않는다. 이 경우 `CF-Connecting-IP`를 접속 IP 근거로 쓰지 않는다. 프록시 모드로 바꾸면 공식 IP 범위와 원본 서버 접근 제한을 포함해 신뢰 구조를 다시 설계한다. [Cloudflare DNS 설명](https://developers.cloudflare.com/dns/proxy-status/)

## 3. 프록시 설정

### 직접 관리하는 외부 Nginx

외부 Nginx가 이용자의 접속을 직접 받는 경우, 애플리케이션으로 보내는 헤더를 확인된 주소 하나로 덮어쓰는 구성이 단순하다.

```nginx
# 외부 HTTPS 서버의 프록시 location 안에 배치하는 예시.
# $remote_addr가 이용자 접속 IP임을 먼저 확인한다.
proxy_set_header X-Forwarded-For $remote_addr;
proxy_set_header X-Real-IP $remote_addr;
proxy_set_header Forwarded "";
proxy_set_header X-Forwarded-Proto $scheme;
```

이 외부 서버에 사설망 전체를 신뢰하는 real-IP 설정이 상속되어 있다면 `$remote_addr`부터 위조될 수 있다. 생성된 전체 설정을 `nginx -T`로 확인하고 신뢰 범위를 바로잡는다. CDN·다른 프록시가 앞에 있으면 그 프록시를 검증하는 단계가 추가로 필요하다.

### Nginx Proxy Manager를 쓰는 경우

NPM의 기본 헤더·real-IP 설정은 설치 버전의 실제 설정으로 확인한다. Advanced가 비어 있어도 상위 설정이 적용될 수 있다. `docker exec npm nginx -T` 출력은 로컬에서 필요한 부분만 확인하고, 전체 내용을 공개 문서에 붙이지 않는다.

이번에 검증한 NPM은 X-Forwarded-For의 마지막에 자신이 확인한 접속 주소를 추가했다. 앞부분에는 외부가 보낸 가짜 주소가 남을 수 있으므로 내부에서 첫 번째 값을 꺼내 쓰지 않는다. [NPM 기본 전달 설정](https://github.com/NginxProxyManager/nginx-proxy-manager/blob/develop/docker/rootfs/etc/nginx/conf.d/include/proxy.conf)

NPM이 이용자 접속을 직접 받는데 상위 사설망 신뢰 설정 때문에 가짜 X-Real-IP를 받아들이는 경우, 해당 Proxy Host의 Advanced에서 다음과 같이 신뢰 목록을 제한한 뒤 검증할 수 있다.

```nginx
set_real_ip_from 127.0.0.1;
```

이 설정은 **이번 직접 접속 구조에서 사용한 설정**이다. NPM 앞에 DSM 역방향 프록시·CDN·다른 로컬 프록시가 있다면 그대로 복사하지 않는다. 그 프록시가 헤더를 올바르게 만들도록 하고 실제 연결 상대만 신뢰해야 한다. 서버 수준 신뢰 목록은 설정이 없을 때 상위 목록을 상속한다. [Nginx 설정 병합 구현](https://github.com/nginx/nginx/blob/master/src/http/modules/ngx_http_realip_module.c)

### 프로젝트 내부 Nginx

다음 예시는 앞단 프록시가 X-Forwarded-For를 올바른 주소 하나로 덮어쓰거나, 올바른 접속 주소를 마지막에 추가하는 구조에만 적용한다.

```nginx
# server 블록 안. 문서용 예시 IP를 실제 연결 상대 IP로 바꾼다.
set_real_ip_from 192.0.2.10;
real_ip_header X-Forwarded-For;
real_ip_recursive off;

location / {
    proxy_pass http://app:8080;
    proxy_set_header Host example.com;
    proxy_set_header X-Forwarded-Host example.com;
    proxy_set_header X-Forwarded-For $remote_addr;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header Forwarded "";

    # 외부 진입이 HTTPS 443으로 고정된 서비스의 예시.
    proxy_set_header X-Forwarded-Proto https;
    proxy_set_header X-Forwarded-Port 443;
}
```

`real_ip_recursive off`는 신뢰한 상대가 보낸 헤더의 마지막 IP를 선택한다. 여러 프록시가 그대로 목록을 누적하는 구조라면 마지막 값이 이용자 IP가 아닐 수 있으므로, 각 단계에서 주소를 확정하거나 전체 경로에 맞는 신뢰 목록을 설계한다. `on`으로만 바꾸고 끝내지 않는다. `$realip_remote_addr`는 헤더 적용 전 연결 상대를 보존하므로 진단에 사용할 수 있다. [Nginx real-IP 설명](https://nginx.org/en/docs/http/ngx_http_realip_module.html)

일반 Nginx 설정은 `${TRUSTED_PROXY}` 같은 환경변수를 자동 치환하지 않는다. 환경변수를 쓰려면 이미지의 템플릿·envsubst 설정을 확인하고, `$remote_addr` 같은 Nginx 변수가 치환되지 않도록 치환 대상도 제한한다.

## 4. 애플리케이션과 로그

- 프레임워크의 공식 프록시 설정을 사용하고 신뢰할 연결 상대를 제한한다. 모든 프록시를 신뢰하는 옵션을 무조건 켜지 않는다.
- 임의의 헤더를 직접 읽거나 쉼표 기준 첫 번째 값을 고르는 코드를 만들지 않는다. 검증된 요청 IP를 읽는 공통 경로를 사용한다.
- IPv4와 IPv6를 모두 저장할 수 있게 한다. PostgreSQL이라면 `inet` 타입을 고려하고, 문자열 컬럼이라면 IPv6 주소를 수용하도록 설계한다.
- 로그인·권한 변경 등 IP가 필요한 사건에 서버 시간·요청 ID·검증된 IP를 함께 기록한다. 비밀번호·인증 코드·토큰·Cookie는 로그에 넣지 않는다.
- 수집 목적에 맞게 보관 기간과 조회 권한을 정한다. 오류 분석을 위해 켠 상세 헤더 로그는 검사 후 끈다.
- 로그인 시 IP를 저장하는 세션은 설정 변경 후 새 로그인으로 확인한다. 과거 세션의 IP가 자동으로 수정된다고 가정하지 않는다.

Keycloak에서 X-Forwarded 계열을 쓸 때는 `KC_PROXY_HEADERS=xforwarded`와 프록시의 헤더 정리가 함께 필요하다. Keycloak 직접 접근은 제한하고, 추가 신뢰 주소 옵션은 설치 버전의 지원 여부를 확인한다. [Keycloak 역방향 프록시 안내](https://www.keycloak.org/server/reverseproxy)

Nginx에서 IP 확인만 필요한 경우의 최소 로그 예시:

```nginx
# http 블록 안
log_format client_ip '$time_iso8601 client=$remote_addr peer=$realip_remote_addr status=$status';

# 기록할 server/location 안
access_log /var/log/nginx/client-ip.log client_ip;
```

로그 출력 위치·회전·보관 정책은 배포 방식에 맞춘다. 이 예시는 요청 본문·쿼리 문자열·인증 헤더를 기록하지 않는다.

## 5. 검증 완료 조건

외부 IP가 한 번 보이는 것과 안전하게 기록되는 것은 별도로 확인한다.

1. 휴대폰 Wi-Fi를 끄고 LTE/5G로 새 로그인해 외부 접속 주소가 기록되는지 확인한다.
2. 내부 Wi-Fi에서도 검사하되 공유기 주소가 보이면 NAT로 주소가 바뀌는 구간을 구분한다.
3. 가짜 X-Forwarded-For, X-Real-IP, Forwarded를 각각 보낸 요청의 기록이 가짜 값으로 바뀌지 않는지 확인한다.
4. 허용되지 않은 기기에서 내부 포트에 직접 접근할 수 없는지 검사한다. 프록시를 통한 정상 접속은 계속 성공해야 한다.
5. 서비스에서 IPv6를 허용하면 IPv6 전달·접근 제한도 확인한다.
6. 컨테이너 재생성 후 다시 검사하고, 호스트 재부팅 검사는 가능한 유지보수 시간에 수행한다. 수행하지 못한 항목은 미검증으로 남긴다.

아래 요청은 진단 예시다. PowerShell에서는 `curl` 대신 `curl.exe`를 사용한다. 자신이 관리하는 서비스의 안전한 검사 URL로 바꾼다.

```sh
curl -sS -o /dev/null -H 'X-Forwarded-For: 198.51.100.99' https://example.com/healthz
curl -sS -o /dev/null -H 'X-Real-IP: 203.0.113.99' https://example.com/healthz
curl -sS -o /dev/null -H 'Forwarded: for=198.51.100.99;proto=http' https://example.com/healthz
```

Windows에서는 `/dev/null` 대신 `NUL`을 쓴다. HTTP 성공 코드만으로는 IP 검증이 끝나지 않는다. 해당 요청의 서버 기록에서 주소를 확인한다. 진단용 IP 조회 URL을 만들었다면 접근을 제한하고 검사 후 제거한다.

## 6. NAS·Docker에서 게이트웨이 IP만 보일 때

애플리케이션 → 내부 Nginx → NPM 순서로 확인해 최초로 주소가 사라지는 구간을 찾는다. NAS에서는 먼저 다음을 조회한다.

```sh
sudo docker port npm
sudo iptables -t nat -S
```

Docker가 사용하는 방화벽 방식과 NAS의 체인 구성을 확인한다. Docker의 NAT 전달 규칙이 있어도 그 규칙에 도달하는 연결이 빠져 있는지 살핀다. Docker가 관리하는 규칙을 통째로 삭제하거나 전역 NAT 규칙을 무작정 추가하지 않는다. Docker 게시 포트는 일반 호스트 방화벽과 처리 경로가 다를 수 있으므로 실제 접근 차단까지 검사한다. [Docker 방화벽 설명](https://docs.docker.com/engine/network/packet-filtering-firewalls/)

이번 헤놀로지에서는 NPM의 기존 5443→443 전달 규칙으로 들어가는 연결이 빠져 있어, NAS의 해당 목적지 IP·TCP 포트로만 연결 규칙을 추가했다. 부팅 작업에 중복 방지 조건과 Docker 체인 준비 대기를 넣었고, 등록 후 수동 실행했다. 이는 **확인된 장애에 대한 환경별 조치**이며 다른 NAS에 기본 설정으로 적용하지 않는다. 실제 재부팅 후 동작은 별도 검증 대상이다.

프로젝트를 다른 PC·NAS로 옮기면 Docker 게이트웨이·프록시 주소·방화벽 적용 경로가 바뀔 수 있다. 이전 프로젝트의 `172.x.x.x` 주소를 그대로 복사하지 않는다.

## 7. 새 프로젝트의 AGENTS.md에 넣을 지침

아래 블록은 독립적으로 복사해 사용할 수 있다.

```markdown
## 실제 접속 IP 기록

- 배포 전 이용자 → 외부 프록시 → 내부 프록시 → 애플리케이션 경로를 확인한다.
- 외부 프록시에서 접속 IP를 확정하고, 내부 서비스는 지정한 프록시가 전달한 값만 신뢰한다.
- X-Forwarded-For 첫 번째 값이나 임의의 IP 헤더를 직접 신뢰하지 않는다.
- 사설망 전체·모든 프록시를 일괄 신뢰하지 않는다. 실제 연결 상대와 필요한 최소 범위를 사용한다.
- 내부 포트는 앞단 프록시만 접근하도록 제한한다. Docker가 접속자를 같은 게이트웨이로 표시하면 호스트·네트워크 접근 제한을 먼저 검증한다.
- Cloudflare DNS 전용에서는 CF-Connecting-IP를 사용하지 않는다. CDN·프록시 추가 시 신뢰 경로를 다시 검증한다.
- 프레임워크의 공식 프록시 기능으로 검증된 IP를 읽고 IPv4·IPv6를 모두 수용한다.
- LTE/5G 새 접속, 가짜 IP 헤더 거부, 내부 포트 우회 차단, 재생성 후 유지를 검증한다. 실행하지 못한 검사는 미검증으로 기록한다.
- IP는 개인·기기의 고유 식별자로 쓰지 않는다. 필요한 사건에만 기록하고 보관 기간·조회 권한을 정한다.
- 비밀번호·토큰·인증 코드는 기록하지 않는다. 임시 진단 로그와 조회 URL은 검사 후 제거한다.
- 환경별 주소·포트·방화벽·재부팅 복원·되돌리기 절차를 문서화한다. 다른 프로젝트의 NAS NAT 규칙이나 Docker IP를 그대로 복사하지 않는다.
```
