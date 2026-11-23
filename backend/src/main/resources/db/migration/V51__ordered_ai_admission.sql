-- Bounded, expiring admission tickets shared by backend instances.
CREATE TABLE ai_call_waiters (
    id UUID PRIMARY KEY,
    sequence BIGSERIAL NOT NULL UNIQUE,
    background BOOLEAN NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_ai_call_waiters_expiry ON ai_call_waiters(expires_at);
