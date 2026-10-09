CREATE TABLE IF NOT EXISTS assistant_evaluation_runs (
    id BIGSERIAL PRIMARY KEY,
    run_id VARCHAR(36) NOT NULL UNIQUE,
    mode VARCHAR(16) NOT NULL,
    status VARCHAR(20) NOT NULL,
    case_count INTEGER NOT NULL,
    completed_count INTEGER NOT NULL DEFAULT 0,
    passed_count INTEGER NOT NULL DEFAULT 0,
    failed_count INTEGER NOT NULL DEFAULT 0,
    error_count INTEGER NOT NULL DEFAULT 0,
    hard_gate_failure_count INTEGER NOT NULL DEFAULT 0,
    pass_rate DOUBLE PRECISION NOT NULL DEFAULT 0,
    average_score DOUBLE PRECISION NOT NULL DEFAULT 0,
    elapsed_ms BIGINT NOT NULL DEFAULT 0,
    generation_model VARCHAR(160),
    judge_model VARCHAR(160),
    independent_judge BOOLEAN NOT NULL DEFAULT FALSE,
    started_by VARCHAR(64),
    message VARCHAR(1000),
    result_json TEXT,
    started_at TIMESTAMP NOT NULL,
    finished_at TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_assistant_evaluation_runs_started
    ON assistant_evaluation_runs(started_at DESC);

CREATE INDEX IF NOT EXISTS idx_assistant_evaluation_runs_status
    ON assistant_evaluation_runs(status, updated_at DESC);
