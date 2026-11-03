-- Historical records keep an empty environment; only new writes populate it.
ALTER TABLE trace_conversation ADD COLUMN IF NOT EXISTS environment String DEFAULT '';
ALTER TABLE long_task_node_execution_log ADD COLUMN IF NOT EXISTS environment String DEFAULT '';
