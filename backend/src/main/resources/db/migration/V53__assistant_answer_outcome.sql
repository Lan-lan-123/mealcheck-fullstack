-- Keep answer completeness separate from successful delivery of the response.
ALTER TABLE assistant_conversation_messages ADD COLUMN outcome_json TEXT;
