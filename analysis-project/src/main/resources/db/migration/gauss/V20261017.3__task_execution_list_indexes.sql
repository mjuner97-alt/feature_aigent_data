-- Supports paged user-scoped lists without sorting all rows across conversation_id groups.
CREATE INDEX IF NOT EXISTS idx_flow_execution_user_created_id
    ON skill_flow_execution(trigger_user_id, created_at DESC, id DESC);
CREATE INDEX IF NOT EXISTS idx_flow_execution_created_id
    ON skill_flow_execution(created_at DESC, id DESC);
