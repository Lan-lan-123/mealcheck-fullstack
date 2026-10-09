CREATE TABLE meal_analysis_requests (
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    request_key VARCHAR(80) NOT NULL,
    payload_hash VARCHAR(64) NOT NULL,
    effective_goal VARCHAR(32) NOT NULL,
    status VARCHAR(20) NOT NULL,
    attempt_token VARCHAR(36) NOT NULL,
    lease_until TIMESTAMP NOT NULL,
    record_id BIGINT REFERENCES meal_records(id) ON DELETE SET NULL,
    response_json TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id, request_key)
);
ALTER TABLE meal_records ADD COLUMN original_foods_json TEXT;
UPDATE meal_records SET original_foods_json = detected_foods_json;
ALTER TABLE meal_records ADD COLUMN revision BIGINT NOT NULL DEFAULT 0;
ALTER TABLE meal_records ADD COLUMN scoring_version VARCHAR(80) NOT NULL DEFAULT 'legacy';
ALTER TABLE meal_records ADD COLUMN recognition_model VARCHAR(100);
CREATE TABLE meal_corrections (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    record_id BIGINT NOT NULL REFERENCES meal_records(id) ON DELETE CASCADE,
    revision BIGINT NOT NULL,
    before_foods_json TEXT NOT NULL,
    after_foods_json TEXT NOT NULL,
    reason VARCHAR(500) NOT NULL,
    scoring_version VARCHAR(80) NOT NULL,
    evaluation_status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    evaluation_foods_json TEXT,
    expected_score INTEGER CHECK (expected_score BETWEEN 0 AND 100),
    reviewed_by VARCHAR(64),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (record_id, revision)
);
CREATE INDEX idx_meal_corrections_created ON meal_corrections(created_at DESC);

-- Invalidated jobs remain queryable but no longer prevent a fresh same-day job.
ALTER TABLE weekly_report_jobs DROP CONSTRAINT uk_weekly_report_jobs_user_week_days;
CREATE UNIQUE INDEX uq_weekly_report_current_job ON weekly_report_jobs(user_id, report_week, days)
    WHERE status <> 'STALE';
