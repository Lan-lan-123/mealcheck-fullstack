CREATE TABLE IF NOT EXISTS weekly_report_jobs (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    report_week DATE NOT NULL,
    days INTEGER NOT NULL,
    status VARCHAR(20) NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0,
    next_retry_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_error VARCHAR(1000),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_weekly_report_jobs_user_week_days UNIQUE (user_id, report_week, days)
);

CREATE INDEX IF NOT EXISTS idx_weekly_report_jobs_claim
    ON weekly_report_jobs(status, next_retry_at, id);
