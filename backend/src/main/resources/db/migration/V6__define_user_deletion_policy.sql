-- User-owned data is erased with the account. Operational/audit events are retained
-- for accountability, but their foreign-key link to the deleted account is removed.

ALTER TABLE meal_records
    DROP CONSTRAINT IF EXISTS meal_records_user_id_fkey,
    ADD CONSTRAINT meal_records_user_id_fkey
        FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE;

ALTER TABLE user_goals
    DROP CONSTRAINT IF EXISTS user_goals_user_id_fkey,
    ADD CONSTRAINT user_goals_user_id_fkey
        FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE;

ALTER TABLE weekly_report_snapshots
    DROP CONSTRAINT IF EXISTS weekly_report_snapshots_user_id_fkey,
    ADD CONSTRAINT weekly_report_snapshots_user_id_fkey
        FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE;

ALTER TABLE assistant_conversations
    DROP CONSTRAINT IF EXISTS assistant_conversations_user_id_fkey,
    ADD CONSTRAINT assistant_conversations_user_id_fkey
        FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE;

ALTER TABLE assistant_conversation_messages
    DROP CONSTRAINT IF EXISTS assistant_conversation_messages_conversation_id_fkey,
    ADD CONSTRAINT assistant_conversation_messages_conversation_id_fkey
        FOREIGN KEY (conversation_id) REFERENCES assistant_conversations(id) ON DELETE CASCADE;

ALTER TABLE user_diet_profiles
    DROP CONSTRAINT IF EXISTS user_diet_profiles_user_id_fkey,
    ADD CONSTRAINT user_diet_profiles_user_id_fkey
        FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE;

ALTER TABLE non_food_upload_events
    DROP CONSTRAINT IF EXISTS non_food_upload_events_user_id_fkey,
    ADD CONSTRAINT non_food_upload_events_user_id_fkey
        FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE SET NULL;

ALTER TABLE admin_audit_logs
    DROP CONSTRAINT IF EXISTS admin_audit_logs_admin_id_fkey,
    ADD CONSTRAINT admin_audit_logs_admin_id_fkey
        FOREIGN KEY (admin_id) REFERENCES users(id) ON DELETE SET NULL;
