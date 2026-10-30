ALTER TABLE skill_flow_node_attempt
    ADD COLUMN IF NOT EXISTS execution_ip VARCHAR(64);

COMMENT ON COLUMN skill_flow_node_attempt.execution_ip IS
    '执行该次节点尝试的后端实例 IPv4 地址';
