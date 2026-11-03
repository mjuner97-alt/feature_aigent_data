-- 资源变更主事件表：使用 digital_qa 前缀，避免依赖独立数据库。
CREATE TABLE IF NOT EXISTS digital_qa_resource_change_event
(
    event_id UUID,
    event_no String,
    resource_type LowCardinality(String),
    resource_id String,
    resource_name String,
    operation_type LowCardinality(String),
    operation_summary String DEFAULT '',
    operator_id String,
    operator_name String,
    operator_type LowCardinality(String) DEFAULT 'user',
    source LowCardinality(String) DEFAULT 'ui',
    result LowCardinality(String) DEFAULT 'success',
    error_code String DEFAULT '',
    error_message String DEFAULT '',
    request_id String DEFAULT '',
    client_ip String DEFAULT '',
    user_agent String DEFAULT '',
    before_version String DEFAULT '',
    after_version String DEFAULT '',
    before_hash String DEFAULT '',
    after_hash String DEFAULT '',
    detail_count UInt32 DEFAULT 0,
    created_at DateTime64(3, 'Asia/Shanghai'),
    extra_data String DEFAULT ''
) ENGINE = MergeTree
PARTITION BY toYYYYMM(created_at)
ORDER BY (resource_type, resource_id, created_at, event_id);
-- 审计记录仅保留两个月，降低长期存储成本。
ALTER TABLE digital_qa_resource_change_event
    MODIFY TTL created_at + INTERVAL 2 MONTH;

-- 资源变更明细表：保存字段、节点、配置等前后变化。
CREATE TABLE IF NOT EXISTS digital_qa_resource_change_detail
(
    event_id UUID,
    detail_no UInt32,
    resource_type LowCardinality(String),
    resource_id String,
    target_type LowCardinality(String),
    target_id String DEFAULT '',
    target_name String DEFAULT '',
    field_name String DEFAULT '',
    field_label String DEFAULT '',
    change_type LowCardinality(String),
    before_value String DEFAULT '',
    after_value String DEFAULT '',
    before_value_json String DEFAULT '',
    after_value_json String DEFAULT '',
    display_before_value String DEFAULT '',
    display_after_value String DEFAULT '',
    value_type LowCardinality(String) DEFAULT 'string',
    is_sensitive UInt8 DEFAULT 0,
    is_truncated UInt8 DEFAULT 0,
    sort_order UInt32 DEFAULT 0,
    created_at DateTime64(3, 'Asia/Shanghai')
) ENGINE = MergeTree
PARTITION BY toYYYYMM(created_at)
ORDER BY (event_id, detail_no);
-- 明细与主事件保持相同的两个月保留周期。
ALTER TABLE digital_qa_resource_change_detail
    MODIFY TTL created_at + INTERVAL 2 MONTH;
