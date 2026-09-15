ALTER TABLE user_diet_profiles
    ADD COLUMN IF NOT EXISTS total_score BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS aggregate_initialized BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS food_counts_json TEXT,
    ADD COLUMN IF NOT EXISTS risk_counts_json TEXT,
    ADD COLUMN IF NOT EXISTS goal_counts_json TEXT,
    ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;

UPDATE user_diet_profiles
SET total_score = average_score * total_meals
WHERE total_score = 0 AND total_meals > 0;

CREATE TABLE IF NOT EXISTS user_restrictions (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    username VARCHAR(64) NOT NULL,
    restriction_type VARCHAR(32) NOT NULL,
    blocked_until TIMESTAMP NOT NULL,
    reason VARCHAR(500),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_user_restrictions_username_type UNIQUE (username, restriction_type)
);

CREATE INDEX IF NOT EXISTS idx_user_restrictions_active
    ON user_restrictions(restriction_type, blocked_until DESC, username);
