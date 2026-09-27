-- Regenerate existing images through the bounded worker. Keep thumbnails and
-- original files available while the separate preview is being prepared.
UPDATE file_views v SET state='QUEUED', attempts=0, error_code=NULL,
    started_at=NULL, finished_at=NULL
FROM files f
WHERE f.id=v.file_id AND f.state='READY' AND v.kind='IMAGE' AND v.state='READY';
