-- 长任务报告访问埋点（ClickHouse）。
-- 测试和生产复用同一套 ClickHouse 时，使用 environment 字段隔离数据：
-- TEST=测试环境，PROD=生产环境。当前数据库由连接配置决定。

CREATE TABLE IF NOT EXISTS skill_flow_notification_link_click
(
    event_id             UUID DEFAULT generateUUIDv4(),
    access_token         String DEFAULT '',
    environment          LowCardinality(String),
    notification_id      Nullable(Int64),
    flow_execution_id    Nullable(Int64),
    job_execution_id     Nullable(Int64),
    actor_user_id        String DEFAULT '',
    source               LowCardinality(String),
    event_type           LowCardinality(String),
    logged_in            Bool,
    event_at             DateTime,
    client_ip            Nullable(String),
    user_agent           Nullable(String),
    task_display_name    Nullable(String),
    execution_at         Nullable(DateTime),
    created_at           DateTime DEFAULT now()
)
ENGINE = MergeTree
PARTITION BY (environment, toYYYYMM(event_at))
ORDER BY (environment, event_at, source, event_type, actor_user_id)
TTL event_at + INTERVAL 2 MONTH DELETE
;

ALTER TABLE skill_flow_notification_link_click
    ADD COLUMN IF NOT EXISTS access_token String DEFAULT '';

