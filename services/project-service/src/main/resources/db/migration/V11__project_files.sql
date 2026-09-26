ALTER TABLE projects ADD COLUMN files_enabled boolean NOT NULL DEFAULT true;
-- Preserve existing projects; new projects explicitly opt in.
ALTER TABLE projects ALTER COLUMN files_enabled SET DEFAULT false;
