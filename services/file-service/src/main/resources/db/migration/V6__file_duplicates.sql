CREATE INDEX files_verified_content_idx
    ON files(project_id,environment_id,expected_sha256,size_bytes,completed_at DESC,id)
    WHERE state='READY';
