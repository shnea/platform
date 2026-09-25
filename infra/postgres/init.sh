#!/bin/sh
set -eu
psql --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" --set ON_ERROR_STOP=1 \
  --set project_password="$PROJECT_DB_PASSWORD" \
  --set file_password="$FILE_DB_PASSWORD" \
  --set notification_password="$NOTIFICATION_DB_PASSWORD" \
  --set identity_password="$IDENTITY_DB_PASSWORD" <<'SQL'
CREATE ROLE platform_project LOGIN PASSWORD :'project_password';
CREATE ROLE platform_file LOGIN PASSWORD :'file_password';
CREATE ROLE platform_notification LOGIN PASSWORD :'notification_password';
CREATE ROLE platform_identity LOGIN PASSWORD :'identity_password';
CREATE DATABASE platform_project OWNER platform_project;
CREATE DATABASE platform_file OWNER platform_file;
CREATE DATABASE platform_notification OWNER platform_notification;
CREATE DATABASE platform_identity OWNER platform_identity;
REVOKE ALL ON DATABASE platform_project FROM PUBLIC;
REVOKE ALL ON DATABASE platform_file FROM PUBLIC;
REVOKE ALL ON DATABASE platform_notification FROM PUBLIC;
REVOKE ALL ON DATABASE platform_identity FROM PUBLIC;
REVOKE CONNECT ON DATABASE postgres FROM PUBLIC;
REVOKE CONNECT ON DATABASE template1 FROM PUBLIC;
SQL
