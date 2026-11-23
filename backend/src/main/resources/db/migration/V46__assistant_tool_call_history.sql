-- Preserve actual tool execution evidence alongside the authenticated assistant message.
ALTER TABLE assistant_conversation_messages ADD COLUMN tool_calls_json TEXT;
