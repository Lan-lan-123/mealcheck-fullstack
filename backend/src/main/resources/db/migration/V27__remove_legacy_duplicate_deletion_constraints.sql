-- Hibernate-created legacy schemas can retain a second, randomly named NO ACTION
-- FK beside the V6 deletion-policy FK. It can fire before CASCADE/SET NULL.
-- Remove only redundant restrictive FKs whose columns and referenced columns
-- exactly match a validated V6 policy constraint. No rows are changed.
DO $$
DECLARE duplicate_fk RECORD;
BEGIN
    FOR duplicate_fk IN
        SELECT legacy.conrelid::regclass AS table_name, legacy.conname
        FROM pg_constraint legacy
        JOIN pg_constraint policy
          ON policy.conrelid = legacy.conrelid
         AND policy.confrelid = legacy.confrelid
         AND policy.conkey = legacy.conkey
         AND policy.confkey = legacy.confkey
        WHERE legacy.contype = 'f' AND policy.contype = 'f'
          AND legacy.confdeltype IN ('a', 'r')
          AND policy.confdeltype IN ('c', 'n') AND policy.convalidated
          AND policy.connamespace = 'public'::regnamespace
          AND policy.conname IN (
              'meal_records_user_id_fkey', 'user_goals_user_id_fkey',
              'weekly_report_snapshots_user_id_fkey', 'assistant_conversations_user_id_fkey',
              'assistant_conversation_messages_conversation_id_fkey', 'user_diet_profiles_user_id_fkey',
              'non_food_upload_events_user_id_fkey', 'admin_audit_logs_admin_id_fkey')
    LOOP
        EXECUTE format('ALTER TABLE %s DROP CONSTRAINT %I', duplicate_fk.table_name, duplicate_fk.conname);
    END LOOP;
END $$;
