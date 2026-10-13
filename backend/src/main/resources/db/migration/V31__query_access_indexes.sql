-- Concurrent builds keep normal application writes available. See the .sql.conf companion.
-- IF NOT EXISTS must never silently accept an invalid index left by an interrupted build.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM pg_index i JOIN pg_class c ON c.oid=i.indexrelid
        JOIN pg_namespace n ON n.oid=c.relnamespace
        WHERE n.nspname=current_schema() AND NOT (i.indisvalid AND i.indisready)
          AND c.relname IN ('idx_users_username_lower_trgm','idx_users_display_name_lower_trgm',
              'idx_meal_records_admin_cursor','idx_non_food_events_created',
              'idx_assistant_user_messages_created','idx_task_failure_time',
              'idx_task_failure_operations_failure_time')
    ) THEN
        RAISE EXCEPTION 'Invalid query index from an interrupted build: inspect/drop that index and repair the failed Flyway migration before retrying';
    END IF;
END $$;
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_users_username_lower_trgm
    ON users USING gin (lower(username) gin_trgm_ops);
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_users_display_name_lower_trgm
    ON users USING gin (lower(display_name) gin_trgm_ops);
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_meal_records_admin_cursor
    ON meal_records(created_at DESC,id DESC);
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_non_food_events_created
    ON non_food_upload_events(created_at);
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_assistant_user_messages_created
    ON assistant_conversation_messages(created_at) WHERE role='user';
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_task_failure_time
    ON task_failure_events(failed_at DESC,id DESC);
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_task_failure_operations_failure_time
    ON task_failure_operations(failure_id,created_at DESC);
