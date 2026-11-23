ALTER TABLE meal_records ADD COLUMN advice_status varchar(32) NOT NULL DEFAULT 'LEGACY';
ALTER TABLE meal_records ADD COLUMN advice_failure_code varchar(48);

CREATE TABLE ai_model_admission_state (
    scope varchar(64) PRIMARY KEY,
    cooldown_until timestamptz NOT NULL DEFAULT '1970-01-01 00:00:00+00'
);
CREATE TABLE ai_model_admission_starts (
    id uuid PRIMARY KEY,
    scope varchar(64) NOT NULL,
    started_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    expires_at timestamptz NOT NULL,
    released boolean NOT NULL DEFAULT false,
    tokens bigint NOT NULL CHECK (tokens >= 0)
);
CREATE INDEX idx_ai_model_starts_scope_time ON ai_model_admission_starts(scope, started_at);
