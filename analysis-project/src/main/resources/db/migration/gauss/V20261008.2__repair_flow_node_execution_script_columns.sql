-- Python flow nodes store their script identity and parameters in the execution snapshot.
-- Safe to run against databases where the earlier Python-node migration already ran.
ALTER TABLE skill_flow_node_execution
    ADD COLUMN IF NOT EXISTS script_id VARCHAR(128);

ALTER TABLE skill_flow_node_execution
    ADD COLUMN IF NOT EXISTS script_params_json TEXT;

ALTER TABLE skill_flow_node_execution
    ALTER COLUMN skill_id DROP NOT NULL;

COMMENT ON COLUMN skill_flow_node_execution.script_id IS
    'Python script registry ID snapshot';
COMMENT ON COLUMN skill_flow_node_execution.script_params_json IS
    'Python script parameters JSON snapshot';
