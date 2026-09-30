#!/bin/sh
# Restore production DB backups into an isolated temporary container.
# Optional image rollback requires the operator to have verified schema/config compatibility.
# Usage: sudo sh scripts/recovery-drill.sh <absolute backup directory> [compatible rollback SHA]
set -eu
umask 077
PATH=/usr/local/bin:/var/packages/ContainerManager/target/usr/bin:/var/packages/Docker/target/usr/bin:$PATH
export PATH
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$ROOT"
fail() { printf '%s\n' "$*" >&2; exit 1; }
if [ "${1:-}" != --loaded ]; then
    exec "$ROOT/.tools/dotenvx" run --strict --overload --no-armor --no-native \
        -fk "$ROOT/.env.keys" -f .env.prod -- sh "$ROOT/scripts/recovery-drill.sh" --loaded "$@"
fi
shift
[ "${PLATFORM_MODE:-}" = prod ] && [ "${COMPOSE_PROJECT_NAME:-}" = shnea-platform-prod ] || fail 'Expected production deployment context.'
[ "$#" -ge 1 ] && [ "$#" -le 2 ] || fail 'Pass a private backup directory and optional compatible rollback SHA.'
case "$1" in /*) ;; *) fail 'Backup directory must be absolute.';; esac
[ "$1" != / ] && [ "$1" != "$PLATFORM_DATA_ROOT" ] || fail 'Do not use filesystem root or active data root.'
ROLLBACK=${2:-}
if [ -n "$ROLLBACK" ]; then printf '%s\n' "$ROLLBACK" | grep -Eq '^[a-f0-9]{12}$' || fail 'Rollback requires a 12-character SHA.'; fi
mkdir -p .deploy
mkdir .deploy/lock 2>/dev/null || fail 'Deployment or maintenance is active.'
printf '%s\n' "$$" > .deploy/lock/pid
CURRENT=
NEEDS_RETURN=false
TEMP_CREATED=false
VOLUME_CREATED=false
TEMP_NAME="platform-restore-drill-$(date -u '+%Y%m%d%H%M%S')-$$"
VOLUME="$TEMP_NAME-data"
finish() {
    code=$?
    trap - EXIT HUP INT TERM
    if [ "$NEEDS_RETURN" = true ]; then
        echo 'Returning to the starting release after an interrupted drill.'
        if DEPLOY_PARENT_PID=$$ sh "$ROOT/scripts/deploy.sh" "$CURRENT"; then
            echo 'PASS starting release recovered'
        else
            echo 'FAIL returning to the starting release; inspect production immediately' >&2
            code=1
        fi
    fi
    if [ "$TEMP_CREATED" = true ]; then docker rm -f "$TEMP_NAME" >/dev/null || code=1; fi
    if [ "$VOLUME_CREATED" = true ]; then docker volume rm "$VOLUME" >/dev/null || code=1; fi
    printf '%s recovery-drill exit=%s\n' "$(date -u '+%Y-%m-%dT%H:%M:%SZ')" "$code" >> .deploy/maintenance-history
    rm -f .deploy/lock/pid
    rmdir .deploy/lock
    exit "$code"
}
trap finish EXIT
trap 'exit 130' INT
trap 'exit 143' HUP TERM
CURRENT=$(cat .deploy/current)
printf '%s\n' "$CURRENT" | grep -Eq '^[a-f0-9]{12}$' || fail 'Current SHA is missing.'
mkdir -p "$1"
BACKUP=$(mktemp -d "$1/recovery.XXXXXXXX")
chmod 700 "$BACKUP"
REGISTRY_HOST=${REGISTRY_HOST:-registry.shnea.kr}
DB=$(docker ps -q --filter "label=com.docker.compose.project=$COMPOSE_PROJECT_NAME" --filter label=com.docker.compose.service=db)
[ -n "$DB" ] && [ "$(printf '%s\n' "$DB" | wc -l | tr -d ' ')" = 1 ] || fail 'Expected one running platform database.'
DB_IMAGE=$(docker inspect --format '{{.Image}}' "$DB")
TOOLS_IMAGE=$(docker image inspect --format '{{.Id}}' "$REGISTRY_HOST/platform-tools:$CURRENT")
[ "$(docker exec "$DB" psql -U platform_admin -d postgres -Atc "SELECT count(*) FROM pg_roles WHERE rolname='platform_restore_drill'")" = 0 ] || fail 'Restore bootstrap role already exists in production.'
DATABASES='platform_project platform_file platform_notification platform_identity'
docker exec "$DB" pg_dumpall -U platform_admin -l postgres --globals-only > "$BACKUP/roles.sql"
for database in $DATABASES; do
    [ "$(docker exec "$DB" psql -U platform_admin -d "$database" -Atc 'SELECT count(*) FROM pg_largeobject_metadata')" = 0 ] || fail 'Large objects require an additional restore verification method.'
    docker exec "$DB" pg_dump -U platform_admin -d "$database" --format=custom --create > "$BACKUP/$database.dump"
    [ -s "$BACKUP/$database.dump" ] || fail 'Empty database backup.'
done
(cd "$BACKUP" && sha256sum roles.sql *.dump > SHA256SUMS)
printf '%s\n' "$CURRENT" > "$BACKUP/source-tag"
cp .deploy/current-images "$BACKUP/source-images"
echo 'PASS production roles and four database backups created; originals unchanged'
docker volume inspect "$VOLUME" >/dev/null 2>&1 && fail 'Temporary restore volume already exists.'
docker volume create "$VOLUME" >/dev/null
VOLUME_CREATED=true
# Empty init scripts: never execute platform bootstrap or connect restored apps.
docker inspect "$TEMP_NAME" >/dev/null 2>&1 && fail 'Temporary restore container already exists.'
TEMP_CREATED=true
docker run -d --name "$TEMP_NAME" --network none --memory 512m --pids-limit 256 \
    --mount "type=volume,source=$VOLUME,target=/var/lib/postgresql/data" \
    --mount type=tmpfs,destination=/docker-entrypoint-initdb.d \
    -e POSTGRES_USER=platform_restore_drill -e POSTGRES_DB=postgres -e POSTGRES_HOST_AUTH_METHOD=trust \
    "$DB_IMAGE" >/dev/null
attempt=0
until docker exec "$TEMP_NAME" pg_isready -h 127.0.0.1 -U platform_restore_drill -d postgres >/dev/null 2>&1; do
    attempt=$((attempt + 1)); [ "$attempt" -le 60 ] || fail 'Temporary restore database did not start.'
    sleep 1
done
docker exec -i "$TEMP_NAME" psql -U platform_restore_drill -d postgres -v ON_ERROR_STOP=1 < "$BACKUP/roles.sql" > "$BACKUP/roles-restore.log"
: > "$BACKUP/results.jsonl"
for database in $DATABASES; do
    docker exec -i "$TEMP_NAME" pg_restore -U platform_restore_drill -d postgres --create --exit-on-error < "$BACKUP/$database.dump" 2> "$BACKUP/$database-restore.log"
    docker exec -i "$TEMP_NAME" pg_restore --data-only --no-owner --no-privileges --file=- < "$BACKUP/$database.dump" > "$BACKUP/$database-source.sql"
    docker exec "$TEMP_NAME" pg_dump -U platform_restore_drill -d "$database" --data-only --no-owner --no-privileges > "$BACKUP/$database-restored.sql"
    result=$(docker run --rm --user 0:0 --network none --read-only --cap-drop ALL --security-opt no-new-privileges \
        --mount "type=bind,source=$BACKUP,target=/backup,readonly" --entrypoint python "$TOOLS_IMAGE" \
        /tools/verify_restore.py "/backup/$database-source.sql" "/backup/$database-restored.sql")
    printf '{"database":"%s","verification":%s}\n' "$database" "$result" >> "$BACKUP/results.jsonl"
    printf 'PASS %s restored and verified: %s\n' "$database" "$result"
    rm -f "$BACKUP/$database-source.sql" "$BACKUP/$database-restored.sql"
done
cp "$BACKUP/results.jsonl" .deploy/last-restore-result.jsonl
if [ -n "$ROLLBACK" ] && [ "$ROLLBACK" != "$CURRENT" ]; then
    echo "Starting compatible image rollback drill: $CURRENT -> $ROLLBACK -> $CURRENT"
    NEEDS_RETURN=true
    DEPLOY_PARENT_PID=$$ sh "$ROOT/scripts/deploy.sh" "$ROLLBACK"
    printf '%s rollback-healthy=%s\n' "$(date -u '+%Y-%m-%dT%H:%M:%SZ')" "$ROLLBACK" >> "$BACKUP/drill-history"
    DEPLOY_PARENT_PID=$$ sh "$ROOT/scripts/deploy.sh" "$CURRENT"
    NEEDS_RETURN=false
    printf '%s forward-healthy=%s\n' "$(date -u '+%Y-%m-%dT%H:%M:%SZ')" "$CURRENT" >> "$BACKUP/drill-history"
    cp "$BACKUP/drill-history" .deploy/last-rollback-drill
fi
echo 'PASS recovery drill complete; backups retained privately, temporary container and volume will be removed'
