#!/bin/sh
# Run inside the built platform-nginx image; no host ports or real accounts.
set -eu
sed -i 's/worker_processes  auto/worker_processes  1/; s/error.log notice/error.log warn/' /etc/nginx/nginx.conf
sed -i 's/keycloak:8080/127.0.0.1:18081/g' /etc/nginx/templates/default.conf.template
cat > /etc/nginx/conf.d/ip-check.conf <<'EOF'
server {
    listen 127.0.0.1:18081;
    access_log off;
    location / {
        return 200 "$http_x_forwarded_for|$http_x_forwarded_proto|$http_forwarded|$http_x_forwarded_port";
    }
}
EOF
export KEYCLOAK_PUBLIC_URL=https://platform.shnea.kr/auth
export NGINX_ENVSUBST_FILTER='^(KEYCLOAK_PUBLIC_URL|NGINX_TRUSTED_PROXY)$'

check() {
    expected="$1"
    path="$2"
    shift 2
    actual=$(wget -q -O - "$@" "http://127.0.0.1:8080$path")
    if [ "$actual" != "$expected|https||" ]; then
        echo "FAIL: $path expected $expected|https||, got $actual" >&2
        exit 1
    fi
}

for trusted in 127.0.0.2 127.0.0.1; do
    export NGINX_TRUSTED_PROXY="$trusted"
    /docker-entrypoint.sh nginx -t
    nginx
    for path in /auth/proxy-check /auth/social/naver/callback; do
        check 127.0.0.1 "$path"
        check 127.0.0.1 "$path" --header 'X-Forwarded-For: invalid'
        expected=127.0.0.1
        [ "$trusted" != 127.0.0.1 ] || expected=203.0.113.7
        check "$expected" "$path" \
            --header 'X-Forwarded-For: 198.51.100.99, 203.0.113.7' \
            --header 'X-Forwarded-Proto: http' \
            --header 'Forwarded: for=198.51.100.99;proto=http' \
            --header 'X-Forwarded-Port: 1234'
        [ "$trusted" != 127.0.0.1 ] || expected=2001:db8::7
        check "$expected" "$path" --header 'X-Forwarded-For: 198.51.100.99, 2001:db8::7'
    done
    nginx -s stop
    # Wait until both listening sockets have been released before the next case.
    while [ -e /var/run/nginx.pid ]; do sleep 0.1; done
done
echo 'PASS: trusted/untrusted peers, IPv4/IPv6, missing/invalid IP, spoofed chain and TLS headers (16 checks)'
