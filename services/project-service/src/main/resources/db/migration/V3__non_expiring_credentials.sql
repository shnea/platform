-- NULL means no expiry. Existing keys retain their original expiry and revocation state.
ALTER TABLE service_credentials ALTER COLUMN expires_at DROP NOT NULL;
