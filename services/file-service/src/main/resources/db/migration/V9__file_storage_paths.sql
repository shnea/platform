-- NULL preserves the original flat layout, including unfinished uploads.
ALTER TABLE files ADD COLUMN storage_path text;
CREATE UNIQUE INDEX files_storage_path ON files(storage_path) WHERE storage_path IS NOT NULL;
