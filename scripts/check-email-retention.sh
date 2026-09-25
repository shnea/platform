#!/bin/sh
# Runs against notification-service's own DB with disposable random rows only.
set -eu
export PGPASSWORD="$NOTIFICATION_DB_PASSWORD"
sql() { psql -X -qAt -v ON_ERROR_STOP=1 -h db -U platform_notification -d platform_notification -c "$1"; }
fixture=$(sql "SELECT gen_random_uuid()")
cleanup() { sql "DELETE FROM identity_email WHERE environment_id='$fixture'" >/dev/null; }
trap cleanup EXIT
sql "INSERT INTO identity_email(id,environment_id,fingerprint,delivery,state,text_body,created_at) VALUES
 (gen_random_uuid(),'$fixture','fresh','MOCK','MOCK','retention-fixture',now()),
 (gen_random_uuid(),'$fixture','expired','MOCK','MOCK','retention-fixture',now()-interval '61 minutes'),
 (gen_random_uuid(),'$fixture','interrupted','NCP','SENDING',NULL,now()-interval '3 minutes'),
 (gen_random_uuid(),'$fixture','purge','MOCK','MOCK',NULL,now()-interval '31 days')" >/dev/null
echo 'Retention fixtures ready; waiting for notification scheduler (up to 90 seconds).'
attempt=0
while [ "$attempt" -lt 18 ]; do
    result=$(sql "SELECT count(*) FROM identity_email WHERE environment_id='$fixture' AND
      ((fingerprint='fresh' AND text_body='retention-fixture') OR
       (fingerprint='expired' AND text_body IS NULL) OR (fingerprint='interrupted' AND state='UNKNOWN'))")
    total=$(sql "SELECT count(*) FROM identity_email WHERE environment_id='$fixture'")
    if [ "$result" = 3 ] && [ "$total" = 3 ]; then
        echo 'PASS fresh body preserved, expired body removed, interrupted send marked UNKNOWN, 30-day metadata purged'
        exit 0
    fi
    sleep 5
    attempt=$((attempt+1))
done
echo 'FAIL notification scheduler did not complete retention checks' >&2
exit 1
