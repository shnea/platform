#!/bin/sh
# Isolated disposable nginx container only. No ports, credentials or real services.
set -eu
sed -i 's/worker_processes  auto/worker_processes  1/; s/error.log notice/error.log warn/' /etc/nginx/nginx.conf
sed -i 's/project-service:8080/127.0.0.1:18081/; /proxy_pass http:\/\/\$projects;/a\        proxy_read_timeout 200ms;' /etc/nginx/templates/default.conf.template
export KEYCLOAK_PUBLIC_URL=https://platform.shnea.kr/auth
export NGINX_TRUSTED_PROXY=127.0.0.2
export NGINX_ENVSUBST_FILTER='^(KEYCLOAK_PUBLIC_URL|NGINX_TRUSTED_PROXY)$'
/docker-entrypoint.sh nginx -t
nginx

check() {
    expected=$1
    code=$2
    shift 2
    actual=$(curl -sS --max-time 4 -D /tmp/headers -o /tmp/body -w '%{http_code}' "$@" 'http://127.0.0.1:8080/api/v1/probe?secret=gateway-private-sentinel')
    [ "$actual" = "$expected" ]
    grep -qi '^Content-Type: application/problem+json' /tmp/headers
    id=$(sed -n 's/^X-Request-ID: \([a-f0-9]*\).*/\1/p' /tmp/headers | tr -d '\r')
    [ ${#id} -eq 32 ]
    grep -q "\"code\":\"$code\"" /tmp/body
    grep -q "\"requestId\":\"$id\"" /tmp/body
    grep -qi '^Cache-Control: no-store' /tmp/headers
    ! grep -q 'gateway-private-sentinel' /tmp/body
    echo "PASS gateway $expected $code"
}
check 502 UPSTREAM_UNAVAILABLE
dd if=/dev/zero of=/tmp/large bs=1024 count=1025 2>/dev/null
check 413 PAYLOAD_TOO_LARGE --data-binary @/tmp/large

# A listener that accepts but never answers produces an actual upstream read timeout.
tail -f /dev/null | nc -l -p 18081 >/dev/null &
listener=$!
sleep 0.1
check 504 UPSTREAM_TIMEOUT
kill "$listener" 2>/dev/null || true

# An application's own 503 body must not be replaced by the gateway.
cat > /etc/nginx/conf.d/upstream-check.conf <<'EOF'
server {
    listen 127.0.0.1:18081;
    access_log off;
    location / {
        default_type application/problem+json;
        return 503 '{"code":"APPLICATION_SPECIFIC_ERROR"}';
    }
}
EOF
nginx -s reload
sleep 0.2
status=$(curl -sS --max-time 3 -o /tmp/body -w '%{http_code}' http://127.0.0.1:8080/api/v1/probe)
[ "$status" = 503 ]
grep -q 'APPLICATION_SPECIFIC_ERROR' /tmp/body
echo 'PASS application 503 body preserved'
nginx -s stop
