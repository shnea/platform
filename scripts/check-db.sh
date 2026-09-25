#!/bin/sh
set -eu
export PGHOST=db PGCONNECT_TIMEOUT=5
for owner in project file notification identity; do
    case "$owner" in
        project) export PGPASSWORD="$PROJECT_DB_PASSWORD" ;;
        file) export PGPASSWORD="$FILE_DB_PASSWORD" ;;
        notification) export PGPASSWORD="$NOTIFICATION_DB_PASSWORD" ;;
        identity) export PGPASSWORD="$IDENTITY_DB_PASSWORD" ;;
    esac
    export PGUSER="platform_$owner"
    psql -X -v ON_ERROR_STOP=1 -d "platform_$owner" -c 'SELECT 1' >/dev/null
    for target in project file notification identity; do
        if [ "$owner" != "$target" ]; then
            if psql -X -d "platform_$target" -c 'SELECT 1' >/dev/null 2>&1; then
                echo "FAIL: $owner connected to $target"
                exit 1
            fi
        fi
    done
    # Each owner can migrate its own schema. Roll back this verification.
    psql -X -v ON_ERROR_STOP=1 -d "platform_$owner" -c 'BEGIN; CREATE TABLE foundation_isolation_check(id integer); ROLLBACK;' >/dev/null
    echo "PASS: $owner own DB and schema access; other service DBs denied"
done
