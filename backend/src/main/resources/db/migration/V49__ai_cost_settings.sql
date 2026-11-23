CREATE TABLE ai_cost_settings (
    id SMALLINT PRIMARY KEY CHECK (id = 1),
    version BIGINT NOT NULL DEFAULT 0,
    global_daily_cost NUMERIC(18,6) CHECK (global_daily_cost >= 0),
    user_daily_cost NUMERIC(18,6) CHECK (user_daily_cost >= 0),
    system_daily_cost NUMERIC(18,6) CHECK (system_daily_cost >= 0),
    price_catalog_json TEXT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
INSERT INTO ai_cost_settings(id) VALUES (1);

CREATE TABLE ai_personal_cost_settings (
    user_id BIGINT PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    daily_cost NUMERIC(18,6) NOT NULL DEFAULT 0 CHECK (daily_cost >= 0),
    version BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
