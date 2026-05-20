CREATE INDEX idx_pastes_content_hash
    ON paste.pastes (content_hash)
    WHERE is_deleted = FALSE AND content_type = 'S3';
