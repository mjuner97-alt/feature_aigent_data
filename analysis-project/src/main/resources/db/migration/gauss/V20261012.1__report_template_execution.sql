-- Template executions have no long-task definition; preserve their source and outline snapshot.
ALTER TABLE skill_flow_execution ALTER COLUMN flow_id DROP NOT NULL;
ALTER TABLE skill_flow_execution ADD COLUMN IF NOT EXISTS report_process_id BIGINT;
ALTER TABLE skill_flow_execution ADD COLUMN IF NOT EXISTS report_outline_snapshot TEXT;
