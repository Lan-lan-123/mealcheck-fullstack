-- One opaque dispatch identity per submission; resubmission rotates event_id.
CREATE TABLE meal_analysis_outbox (
    event_id UUID PRIMARY KEY,
    user_id BIGINT NOT NULL,
    request_key VARCHAR(80) NOT NULL,
    next_publish_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    publish_token UUID,
    published_at TIMESTAMP,
    publish_attempts INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (user_id, request_key),
    FOREIGN KEY (user_id, request_key) REFERENCES meal_analysis_requests(user_id, request_key) ON DELETE CASCADE
);
CREATE INDEX idx_meal_analysis_outbox_due ON meal_analysis_outbox(next_publish_at);

-- Pending V17 work remains consumable after upgrading or restoring a database backup.
INSERT INTO meal_analysis_outbox(event_id, user_id, request_key)
SELECT gen_random_uuid(), user_id, request_key FROM meal_analysis_requests
WHERE input_image IS NOT NULL AND status IN ('PENDING', 'PROCESSING');
