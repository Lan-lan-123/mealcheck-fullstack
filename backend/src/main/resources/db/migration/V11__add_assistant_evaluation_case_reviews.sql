CREATE TABLE IF NOT EXISTS assistant_evaluation_case_reviews (
    id BIGSERIAL PRIMARY KEY,
    run_id VARCHAR(36) NOT NULL,
    case_id VARCHAR(160) NOT NULL,
    verdict VARCHAR(10) NOT NULL CHECK (verdict IN ('PASSED', 'FAILED')),
    notes VARCHAR(2000),
    reviewer VARCHAR(64) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_assistant_evaluation_case_review UNIQUE (run_id, case_id),
    CONSTRAINT fk_assistant_evaluation_case_review_run
        FOREIGN KEY (run_id) REFERENCES assistant_evaluation_runs(run_id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_assistant_evaluation_case_reviews_run
    ON assistant_evaluation_case_reviews(run_id, updated_at DESC);
