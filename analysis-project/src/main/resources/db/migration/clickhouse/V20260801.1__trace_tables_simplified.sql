-- ============================================================================
-- V20260801.1__trace_tables_simplified.sql
-- ----------------------------------------------------------------------------
-- 简化后的 Trace 表（替代 V20260730.1__trace_tables.sql）。
--
-- 表结构（仅 2 张）：
--   trace_conversation - 会话级汇总（每条请求 1 行）
--   trace_event        - AgentEvent 原始流（每条事件 1 行，event_json 为 Jackson 多态输出）
--
-- 删除旧 trace_span 表：前端按时间顺序还原用户输入 → 思考 → 回答 → 工具调用，
-- 无需服务端做 Span 配对 / 树构建。
-- ============================================================================

DROP TABLE IF EXISTS trace_span;

CREATE TABLE IF NOT EXISTS trace_conversation (
    conversation_id   String,
    trace_id          String,
    user_id           String,
    source            LowCardinality(String),
    agent_id          String,
    agent_name        String,
    start_ts          DateTime64(3),
    end_ts            DateTime64(3),
    duration_ms       UInt32,
    status            LowCardinality(String),
    error_message     String DEFAULT '',
    event_count       UInt32 DEFAULT 0,
    token_input       UInt32 DEFAULT 0,
    token_output      UInt32 DEFAULT 0,
    model             LowCardinality(String) DEFAULT '',
    event_date        Date DEFAULT toDate(start_ts)
)
ENGINE = MergeTree()
PARTITION BY toYYYYMMDD(event_date)
ORDER BY (event_date, conversation_id)
TTL event_date + INTERVAL 90 DAY
SETTINGS index_granularity = 8192;


CREATE TABLE IF NOT EXISTS trace_event (
    event_id          String,
    conversation_id   String,
    trace_id          String,
    event_type        LowCardinality(String),
    event_name        String,
    source            String DEFAULT '',
    timestamp         DateTime64(3),
    duration_ms       UInt32 DEFAULT 0,
    event_json        String,
    event_date        Date DEFAULT toDate(timestamp)
)
ENGINE = MergeTree()
PARTITION BY toYYYYMMDD(event_date)
ORDER BY (event_date, conversation_id, timestamp)
TTL event_date + INTERVAL 90 DAY
SETTINGS index_granularity = 8192;
