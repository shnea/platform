ALTER TABLE audit_events ADD COLUMN environment_id uuid REFERENCES environments(id);
ALTER TABLE audit_events ADD COLUMN session_id uuid;
