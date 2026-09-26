#!/bin/sh
# First installation only; does not recurse through or change existing data.
set -eu
test "$(id -u)" = 0 || { echo 'Run with sudo.' >&2; exit 1; }
test "$(pwd -P)" = /volume1/docker/prod/platform || { echo 'Run from /volume1/docker/prod/platform.' >&2; exit 1; }
test -d /volume2 || { echo '/volume2 is not mounted.' >&2; exit 1; }
test -f .env || { echo 'Generate .env first.' >&2; exit 1; }
release_tag=$(sed -n 's/^IMAGE_TAG=//p' .env | tr -d '\r')
case "$release_tag" in ''|*[!a-zA-Z0-9_.-]*) echo 'Invalid IMAGE_TAG.' >&2; exit 1;; esac
file_image="registry.shnea.kr/platform-file-service:$release_tag"
file_uid=$(docker run --rm --network none --entrypoint id "$file_image" -u app)
file_gid=$(docker run --rm --network none --entrypoint id "$file_image" -g app)
loki_uid=$(docker image inspect grafana/loki:3.7.0 --format '{{.Config.User}}')
case "$loki_uid" in 10001) ;; *) echo 'Unexpected Loki user; inspect release image first.' >&2; exit 1;; esac
for directory in /volume2/homes/platform /volume2/homes/platform/postgres /volume2/homes/platform/files /volume2/homes/platform/loki; do
    test ! -L "$directory" || { echo "Refusing symbolic link: $directory" >&2; exit 1; }
done
mkdir -p /volume2/homes/platform
test "$(cd /volume2/homes/platform && pwd -P)" = /volume2/homes/platform || { echo 'Unexpected resolved data root.' >&2; exit 1; }
for name in postgres files loki; do
    directory="/volume2/homes/platform/$name"
    if test -d "$directory" && test -n "$(ls -A "$directory")"; then
        echo "Existing data found at $directory; first-install preparation stopped." >&2
        exit 1
    fi
done
mkdir -p /volume2/homes/platform/postgres /volume2/homes/platform/files /volume2/homes/platform/loki
chown "$file_uid:$file_gid" /volume2/homes/platform/files
chown 10001:10001 /volume2/homes/platform/loki
chmod 700 /volume2/homes/platform/postgres /volume2/homes/platform/files /volume2/homes/platform/loki
chmod 600 .env
echo 'Prepared empty data directories. PostgreSQL initializes its own ownership on first start.'
