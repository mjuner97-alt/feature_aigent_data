-- Python 节点兼容扩展。报告大纲字段由 V20260920.1 提供，此处不重复添加。
ALTER TABLE skill_flow_node ADD COLUMN node_type VARCHAR(32) NULL;
ALTER TABLE skill_flow_node ADD COLUMN script_id VARCHAR(128) NULL;
ALTER TABLE skill_flow_node ADD COLUMN script_params_json TEXT NULL;

COMMENT ON COLUMN skill_flow_node.node_type IS '节点类型: PYTHON 或 LEGACY_SKILL; NULL 兼容历史 Skill 节点';
COMMENT ON COLUMN skill_flow_node.script_id IS 'Python 脚本注册表 ID; 每个 Python 节点最多一个';
COMMENT ON COLUMN skill_flow_node.script_params_json IS 'Python 脚本参数 JSON';
