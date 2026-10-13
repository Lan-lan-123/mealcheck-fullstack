ALTER TABLE meal_analysis_requests ADD COLUMN input_image BYTEA;
ALTER TABLE meal_analysis_requests ADD COLUMN input_content_type VARCHAR(80);
ALTER TABLE meal_analysis_requests ADD COLUMN input_extension VARCHAR(12);
ALTER TABLE meal_analysis_requests ADD COLUMN input_filename VARCHAR(255);
ALTER TABLE meal_analysis_requests ADD COLUMN input_width INTEGER;
ALTER TABLE meal_analysis_requests ADD COLUMN input_height INTEGER;
ALTER TABLE meal_analysis_requests ADD COLUMN attempts INTEGER NOT NULL DEFAULT 0;
ALTER TABLE meal_analysis_requests ADD COLUMN next_attempt_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE meal_analysis_requests ADD COLUMN last_error VARCHAR(100);
CREATE INDEX idx_meal_analysis_dispatch ON meal_analysis_requests(next_attempt_at,created_at)
 WHERE input_image IS NOT NULL AND status IN ('PENDING','PROCESSING');
CREATE INDEX idx_meal_analysis_updated ON meal_analysis_requests(updated_at);
CREATE INDEX idx_meal_records_user_created_id ON meal_records(user_id,created_at DESC,id DESC);
CREATE TABLE meal_evaluation_datasets (
 id BIGSERIAL PRIMARY KEY, fingerprint VARCHAR(64) NOT NULL UNIQUE,
 dataset_version VARCHAR(80) NOT NULL, fixtures_json TEXT NOT NULL,
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE meal_evaluation_runs (
 id BIGSERIAL PRIMARY KEY, dataset_id BIGINT NOT NULL REFERENCES meal_evaluation_datasets(id),
 scoring_version VARCHAR(80) NOT NULL, result_json TEXT NOT NULL,
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_meal_evaluation_runs_created ON meal_evaluation_runs(created_at DESC,id DESC);
