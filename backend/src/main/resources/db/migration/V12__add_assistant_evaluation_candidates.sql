CREATE TABLE IF NOT EXISTS assistant_evaluation_candidates (
    id BIGSERIAL PRIMARY KEY,
    question VARCHAR(1000) NOT NULL,
    observed_answer TEXT NOT NULL,
    issue_type VARCHAR(40) NOT NULL,
    sanitized_context VARCHAR(2000),
    expected_skill VARCHAR(80),
    expected_tools_json TEXT,
    notes VARCHAR(2000),
    source_reference VARCHAR(200),
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
    submitted_by VARCHAR(64) NOT NULL,
    reviewed_by VARCHAR(64),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_assistant_evaluation_candidates_status
    ON assistant_evaluation_candidates(status, created_at DESC);
