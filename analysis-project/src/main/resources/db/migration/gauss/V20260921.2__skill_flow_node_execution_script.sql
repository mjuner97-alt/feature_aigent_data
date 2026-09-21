-- Python 节点执行链路:节点执行快照带上脚本与参数,skill_id 允许为空(Python 节点不绑 Skill)。
ALTER TABLE skill_flow_node ALTER COLUMN skill_id DROP NOT NULL;
ALTER TABLE skill_flow_node_execution ALTER COLUMN skill_id DROP NOT NULL;
ALTER TABLE skill_flow_node_execution ADD COLUMN script_id VARCHAR(128) NULL;
ALTER TABLE skill_flow_node_execution ADD COLUMN script_params_json TEXT NULL;

COMMENT ON COLUMN skill_flow_node_execution.skill_id IS 'Skill id 快照;Python 脚本节点为空';
COMMENT ON COLUMN skill_flow_node_execution.script_id IS 'Python 脚本注册表 ID 快照;非空时节点直接执行脚本不走 AI';
COMMENT ON COLUMN skill_flow_node_execution.script_params_json IS 'Python 脚本参数 JSON 快照';
