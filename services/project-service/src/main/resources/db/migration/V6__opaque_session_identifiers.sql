-- Keycloak session IDs are opaque strings, including URL-safe non-UUID values.
ALTER TABLE audit_events ALTER COLUMN session_id TYPE varchar(128) USING session_id::text;
