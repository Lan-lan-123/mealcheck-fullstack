ALTER TABLE meal_analysis_requests ADD COLUMN input_image_ref VARCHAR(512);
CREATE INDEX idx_analysis_image_ref ON meal_analysis_requests(input_image_ref) WHERE input_image_ref IS NOT NULL;
CREATE INDEX idx_meal_image_path ON meal_records(stored_image_path) WHERE stored_image_path IS NOT NULL;
CREATE INDEX idx_budget_reservation_retention ON ai_budget_reservations(created_at,id);
CREATE INDEX idx_budget_reservation_owner_day ON ai_budget_reservations(quota_key,usage_day);
CREATE TABLE weekly_report_dispatch (
    report_week DATE PRIMARY KEY,
    cursor_id BIGINT NOT NULL DEFAULT 0,
    upper_id BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' CHECK(status IN ('ACTIVE','DONE')),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
