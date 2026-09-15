CREATE TABLE IF NOT EXISTS users (
    id BIGSERIAL PRIMARY KEY,
    username VARCHAR(64) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    display_name VARCHAR(64),
    role VARCHAR(20) NOT NULL DEFAULT 'USER',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_upload_at TIMESTAMP
);

CREATE TABLE IF NOT EXISTS meal_records (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id),
    original_file_name VARCHAR(255),
    stored_image_path VARCHAR(500),
    goal VARCHAR(32),
    score INTEGER NOT NULL,
    summary VARCHAR(800),
    detected_foods_json TEXT,
    category_counts_json TEXT,
    risk_tags_json TEXT,
    advice TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS user_goals (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id),
    goal_type VARCHAR(32) NOT NULL,
    start_date DATE,
    end_date DATE,
    note VARCHAR(500),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS weekly_report_snapshots (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id),
    days INTEGER NOT NULL,
    period_start DATE,
    period_end DATE,
    report_json TEXT,
    generated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS non_food_upload_events (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT REFERENCES users(id),
    username VARCHAR(64) NOT NULL,
    display_name VARCHAR(64),
    reason VARCHAR(500),
    window_count BIGINT NOT NULL,
    window_minutes INTEGER NOT NULL,
    observed_minutes INTEGER NOT NULL DEFAULT 1,
    threshold_reached BOOLEAN NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS admin_audit_logs (
    id BIGSERIAL PRIMARY KEY,
    admin_id BIGINT REFERENCES users(id),
    admin_username VARCHAR(64),
    action VARCHAR(64) NOT NULL,
    target_type VARCHAR(64),
    target_id BIGINT,
    detail VARCHAR(1000),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS assistant_conversations (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id),
    title VARCHAR(120) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    memory_summary TEXT,
    memory_state TEXT,
    summarized_message_count INTEGER NOT NULL DEFAULT 0
);

ALTER TABLE assistant_conversations ADD COLUMN IF NOT EXISTS memory_summary TEXT;
ALTER TABLE assistant_conversations ADD COLUMN IF NOT EXISTS memory_state TEXT;
ALTER TABLE assistant_conversations ADD COLUMN IF NOT EXISTS summarized_message_count INTEGER NOT NULL DEFAULT 0;

CREATE TABLE IF NOT EXISTS assistant_conversation_messages (
    id BIGSERIAL PRIMARY KEY,
    conversation_id BIGINT NOT NULL REFERENCES assistant_conversations(id),
    role VARCHAR(20) NOT NULL,
    text TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS user_diet_profiles (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL UNIQUE REFERENCES users(id),
    total_meals BIGINT NOT NULL DEFAULT 0,
    average_score INTEGER NOT NULL DEFAULT 0,
    preferred_goal VARCHAR(32),
    common_foods_json TEXT,
    common_risks_json TEXT,
    profile_summary VARCHAR(1000),
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS rag_search_events (
    id BIGSERIAL PRIMARY KEY,
    query_text TEXT NOT NULL,
    result_count INTEGER NOT NULL,
    top_score DOUBLE PRECISION NOT NULL,
    average_score DOUBLE PRECISION NOT NULL,
    matched_category VARCHAR(64),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
