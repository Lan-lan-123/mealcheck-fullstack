CREATE INDEX idx_analysis_outstanding ON meal_analysis_requests(user_id, request_key)
    WHERE status IN ('PENDING','PROCESSING');

-- Global reservations survive account deletion and can still be settled by recovery.
ALTER TABLE ai_budget_reservations ADD COLUMN global_reservation_id UUID;

CREATE TABLE ai_call_permits (
    id UUID PRIMARY KEY,
    expires_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_ai_call_permits_expiry ON ai_call_permits(expires_at);

CREATE TABLE ai_call_starts (
    id UUID PRIMARY KEY,
    started_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_ai_call_starts_time ON ai_call_starts(started_at);
