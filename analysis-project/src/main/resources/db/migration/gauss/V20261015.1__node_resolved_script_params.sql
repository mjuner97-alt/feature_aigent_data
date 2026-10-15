ALTER TABLE skill_flow_node_execution
    ADD COLUMN IF NOT EXISTS resolved_script_params_json TEXT;

COMMENT ON COLUMN skill_flow_node_execution.resolved_script_params_json IS
    '首次解析后持久化的实际脚本输入；NULL 为尚未解析，自动重试和手动重跑均保留';
