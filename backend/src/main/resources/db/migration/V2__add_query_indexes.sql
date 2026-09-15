CREATE INDEX IF NOT EXISTS idx_meal_records_user_created
    ON meal_records(user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_meal_records_user_goal_created
    ON meal_records(user_id, goal, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_user_goals_user_active_created
    ON user_goals(user_id, active, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_weekly_reports_user_generated
    ON weekly_report_snapshots(user_id, generated_at DESC);
CREATE INDEX IF NOT EXISTS idx_non_food_events_username_created
    ON non_food_upload_events(username, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_admin_audit_created
    ON admin_audit_logs(created_at DESC);
CREATE INDEX IF NOT EXISTS idx_assistant_conversations_user_updated
    ON assistant_conversations(user_id, updated_at DESC);
CREATE INDEX IF NOT EXISTS idx_assistant_messages_conversation_created
    ON assistant_conversation_messages(conversation_id, created_at, id);
CREATE INDEX IF NOT EXISTS idx_rag_search_events_created_at
    ON rag_search_events(created_at DESC);
