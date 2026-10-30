ALTER TABLE long_task_node_execution_log
    ADD COLUMN IF NOT EXISTS execution_ip String DEFAULT '' AFTER script_id;
