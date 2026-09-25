ALTER TABLE service_credentials ADD COLUMN scopes jsonb;
-- Preserve the capabilities of previously issued keys, including revoked/expired keys.
UPDATE service_credentials c SET scopes = CASE WHEN e.kind = 'DEV'
    THEN '["integration:read","auth:mock"]'::jsonb
    ELSE '["integration:read"]'::jsonb END
FROM environments e WHERE e.id = c.environment_id;
ALTER TABLE service_credentials ALTER COLUMN scopes SET NOT NULL;
ALTER TABLE service_credentials ADD CONSTRAINT credential_scopes_array
    CHECK (jsonb_typeof(scopes) = 'array' AND jsonb_array_length(scopes) > 0);
