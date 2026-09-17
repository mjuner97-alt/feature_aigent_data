-- Optional user-facing node label; execution stores the resolved snapshot.
ALTER TABLE skill_flow_node ADD COLUMN IF NOT EXISTS node_name VARCHAR(255);
ALTER TABLE skill_flow_node_execution ADD COLUMN IF NOT EXISTS node_name VARCHAR(255);
