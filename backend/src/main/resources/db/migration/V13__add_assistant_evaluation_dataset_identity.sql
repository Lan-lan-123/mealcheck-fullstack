ALTER TABLE assistant_evaluation_runs
    ADD COLUMN IF NOT EXISTS dataset_version VARCHAR(40),
    ADD COLUMN IF NOT EXISTS dataset_fingerprint VARCHAR(64);

CREATE INDEX IF NOT EXISTS idx_assistant_evaluation_runs_dataset
    ON assistant_evaluation_runs(dataset_fingerprint, started_at DESC);
