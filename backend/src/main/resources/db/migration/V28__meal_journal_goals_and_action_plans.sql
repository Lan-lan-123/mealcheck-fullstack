ALTER TABLE meal_records ADD COLUMN occurred_at TIMESTAMP;
ALTER TABLE meal_records ADD COLUMN meal_type VARCHAR(16) NOT NULL DEFAULT 'UNSPECIFIED';
ALTER TABLE meal_records ADD COLUMN record_source VARCHAR(16) NOT NULL DEFAULT 'PHOTO';
ALTER TABLE meal_records ADD COLUMN time_confirmed BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE meal_records ADD COLUMN metadata_version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE meal_records ADD COLUMN note VARCHAR(500);
UPDATE meal_records SET occurred_at=created_at;
CREATE INDEX idx_meals_user_occurred ON meal_records(user_id,(coalesce(occurred_at,created_at)),id);

-- A missing entry means unknown. Only explicit user declarations mean skipped.
CREATE TABLE meal_slot_declarations (
 user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
 business_day DATE NOT NULL, meal_type VARCHAR(16) NOT NULL CHECK(meal_type IN ('BREAKFAST','LUNCH','DINNER','SNACK')),
 note VARCHAR(500), updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY(user_id,business_day,meal_type)
);
CREATE TABLE behavior_goals (
 id BIGSERIAL PRIMARY KEY, user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
 kind VARCHAR(24) NOT NULL CHECK(kind IN ('RECORD_DAYS_WEEKLY','MEALS_DAILY')),
 target INTEGER NOT NULL CHECK(target BETWEEN 1 AND 7), start_date DATE NOT NULL, end_date DATE NOT NULL,
 active BOOLEAN NOT NULL DEFAULT true, version BIGINT NOT NULL DEFAULT 0,
 created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP, CHECK(end_date>=start_date)
);
CREATE INDEX idx_behavior_goals_user ON behavior_goals(user_id,start_date,end_date);
CREATE TABLE action_plans (
 id BIGSERIAL PRIMARY KEY, user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
 title VARCHAR(120) NOT NULL, action_text VARCHAR(2000) NOT NULL,
 source_type VARCHAR(16) NOT NULL CHECK(source_type IN ('MANUAL','MEAL','ASSISTANT','REPORT')),
 source_record_id BIGINT REFERENCES meal_records(id) ON DELETE SET NULL,
 source_message_id BIGINT REFERENCES assistant_conversation_messages(id) ON DELETE SET NULL,
 source_period_start DATE, source_period_end DATE,
 planned_date DATE NOT NULL, status VARCHAR(16) NOT NULL DEFAULT 'OPEN' CHECK(status IN ('OPEN','DONE','SKIPPED')),
 note VARCHAR(500), version BIGINT NOT NULL DEFAULT 0,
 created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP, updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
 completed_at TIMESTAMPTZ,
 CHECK ((source_period_start IS NULL AND source_period_end IS NULL) OR
        (source_period_start IS NOT NULL AND source_period_end IS NOT NULL AND source_period_end>=source_period_start))
);
CREATE INDEX idx_action_plans_user_date ON action_plans(user_id,planned_date,id);
CREATE TABLE assistant_answer_feedback (
 message_id BIGINT PRIMARY KEY REFERENCES assistant_conversation_messages(id) ON DELETE CASCADE,
 user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
 verdict VARCHAR(16) NOT NULL CHECK(verdict IN ('HELPFUL','UNHELPFUL')),
 category VARCHAR(24) NOT NULL CHECK(category IN ('GENERAL','INCORRECT','IRRELEVANT','UNCLEAR','SERVICE_ERROR')),
 note VARCHAR(500), request_id VARCHAR(64), updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
ALTER TABLE assistant_conversation_messages ADD COLUMN request_id VARCHAR(64);
ALTER TABLE assistant_conversation_messages ADD COLUMN delivery_status VARCHAR(16) NOT NULL DEFAULT 'COMPLETE';

-- Existing timestamps are backfilled unchanged, so historical reports remain valid.
-- Subsequent journal edits invalidate only that user's reports through the service.
