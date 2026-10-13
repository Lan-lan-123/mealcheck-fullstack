-- Separate opt-in snapshots: legacy meals stay disabled and structure scoring is unchanged.
-- ON DELETE CASCADE covers meal deletion and account deletion through meal_records.
CREATE TABLE meal_nutrition_snapshots (
    record_id BIGINT PRIMARY KEY REFERENCES meal_records(id) ON DELETE CASCADE,
    snapshot_json TEXT NOT NULL,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
