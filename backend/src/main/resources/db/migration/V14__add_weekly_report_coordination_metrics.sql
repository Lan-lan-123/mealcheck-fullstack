CREATE TABLE IF NOT EXISTS weekly_report_coordination_runs (
    id BIGSERIAL PRIMARY KEY,
    run_id VARCHAR(36) NOT NULL UNIQUE,
    job_id BIGINT REFERENCES weekly_report_jobs(id) ON DELETE SET NULL,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    status VARCHAR(20) NOT NULL,
    generation_mode VARCHAR(40),
    review_status VARCHAR(40),
    revision_count INTEGER NOT NULL DEFAULT 0,
    step_count INTEGER NOT NULL DEFAULT 0,
    degraded_step_count INTEGER NOT NULL DEFAULT 0,
    failed_step_count INTEGER NOT NULL DEFAULT 0,
    elapsed_ms BIGINT NOT NULL DEFAULT 0,
    error_message VARCHAR(1000),
    started_at TIMESTAMP NOT NULL,
    finished_at TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_weekly_report_coordination_runs_started
    ON weekly_report_coordination_runs(started_at DESC);

CREATE INDEX IF NOT EXISTS idx_weekly_report_coordination_runs_status
    ON weekly_report_coordination_runs(status, updated_at DESC);

CREATE INDEX IF NOT EXISTS idx_weekly_report_coordination_runs_job
    ON weekly_report_coordination_runs(job_id);

CREATE TABLE IF NOT EXISTS weekly_report_coordination_steps (
    id BIGSERIAL PRIMARY KEY,
    run_id VARCHAR(36) NOT NULL REFERENCES weekly_report_coordination_runs(run_id) ON DELETE CASCADE,
    node_name VARCHAR(40) NOT NULL,
    node_type VARCHAR(20) NOT NULL,
    attempt INTEGER NOT NULL,
    status VARCHAR(20) NOT NULL,
    outcome VARCHAR(40),
    ai_generated BOOLEAN,
    fallback_used BOOLEAN NOT NULL DEFAULT FALSE,
    latency_ms BIGINT NOT NULL DEFAULT 0,
    error_message VARCHAR(1000),
    started_at TIMESTAMP NOT NULL,
    finished_at TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_weekly_report_coordination_steps_run
    ON weekly_report_coordination_steps(run_id, started_at, id);

CREATE INDEX IF NOT EXISTS idx_weekly_report_coordination_steps_status
    ON weekly_report_coordination_steps(status, started_at DESC);
