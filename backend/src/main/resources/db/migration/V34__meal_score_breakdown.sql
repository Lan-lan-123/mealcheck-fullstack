-- Old records retain their original score and no invented explanation.
ALTER TABLE meal_records ADD COLUMN score_breakdown_json TEXT;
