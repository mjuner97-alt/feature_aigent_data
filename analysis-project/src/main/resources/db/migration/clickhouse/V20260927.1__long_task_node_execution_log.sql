CREATE TABLE IF NOT EXISTS default.long_task_node_execution_log
(
    log_id String,
    flow_execution_id Int64,
    node_execution_id Int64,
    attempt_id Int64,
    attempt_no UInt32,
    user_id String,
    flow_name String,
    node_key String,
    node_name String,
    script_id String,
    status LowCardinality(String),
    retryable Bool,
    error_code String,
    started_at DateTime64(3),
    completed_at DateTime64(3),
    duration_ms UInt64,
    params_json String,
    output_text String,
    error_message String,
    params_truncated Bool,
    output_truncated Bool,
    error_truncated Bool,
    params_bytes UInt64,
    output_bytes UInt64,
    error_bytes UInt64,
    event_date Date DEFAULT toDate(started_at)
)
ENGINE = MergeTree
PARTITION BY toYYYYMMDD(event_date)
ORDER BY (event_date, user_id, started_at, flow_execution_id, node_execution_id, attempt_no)
TTL event_date + INTERVAL 90 DAY;
