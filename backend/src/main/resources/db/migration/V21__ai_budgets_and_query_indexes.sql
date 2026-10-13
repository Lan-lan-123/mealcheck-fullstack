CREATE TABLE ai_budget_daily (
    quota_key VARCHAR(64) NOT NULL,
    usage_day DATE NOT NULL,
    user_id BIGINT REFERENCES users(id) ON DELETE CASCADE,
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    spent NUMERIC(18,6) NOT NULL DEFAULT 0 CHECK (spent >= 0),
    reserved NUMERIC(18,6) NOT NULL DEFAULT 0 CHECK (reserved >= 0),
    PRIMARY KEY (quota_key, usage_day)
);
CREATE TABLE ai_budget_reservations (
    id UUID PRIMARY KEY,
    quota_key VARCHAR(64) NOT NULL,
    usage_day DATE NOT NULL,
    amount NUMERIC(18,6) NOT NULL CHECK (amount >= 0),
    status VARCHAR(16) NOT NULL CHECK (status IN ('RESERVED','SENT','SETTLED','RELEASED')),
    charged NUMERIC(18,6),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (quota_key,usage_day) REFERENCES ai_budget_daily(quota_key,usage_day) ON DELETE CASCADE
);
CREATE INDEX idx_ai_budget_unsettled ON ai_budget_reservations(created_at) WHERE status IN ('RESERVED','SENT');
CREATE INDEX IF NOT EXISTS idx_assistant_messages_cursor ON assistant_conversation_messages(conversation_id,created_at DESC,id DESC);
CREATE INDEX IF NOT EXISTS idx_meal_records_user_time_id ON meal_records(user_id,created_at,id);
