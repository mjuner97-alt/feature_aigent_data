-- ============================================================================
-- V20260730.1__trace_tables.sql
-- ----------------------------------------------------------------------------
-- AgentScope Java Trace Monitoring - ClickHouse DDL migration (three tables).
--
-- Source design doc: docs/superpowers/specs/2026-07-30-agentscope-trace-monitoring-design.md
--                    §4.2 / §4.3 / §4.4 / §4.7
--
-- Tables:
--   trace_conversation - conversation-level rollup (one row per conversation)
--   trace_span         - span-level execution trace (parent/child spans, LLM calls)
--   trace_event        - fine-grained event log (hooks / agent events / l2 hooks)
--
-- Design notes:
--   - Partitioned by day via toYYYYMMDD(event_date).
--   - TTL 90 days, auto-expire (event_date + INTERVAL 90 DAY).
--   - Enum-like fields use LowCardinality(String) for storage/scan efficiency.
--   - JSON payloads stored as String; parsing handled in application layer
--     (no Object('json') usage, per design §4.7).
--   - No foreign keys (ClickHouse has no FK support); creation order irrelevant.
--
-- `source` field semantics:
--   - trace_conversation / trace_span.source : 'v1_chat' / 'v2_chat' or 'main' / sub-agent name.
--   - trace_event.source                     : 'hook' / 'agent_event' / 'l2_hook'.
-- ============================================================================


CREATE TABLE trace_conversation (
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
    span_count        UInt16 DEFAULT 0,
    event_count       UInt32 DEFAULT 0,
    token_input       UInt32 DEFAULT 0,
    token_output      UInt32 DEFAULT 0,
    total_cost        Decimal(10,6) DEFAULT 0,
    model             LowCardinality(String),
    metadata          String DEFAULT '{}',
    event_date        Date DEFAULT toDate(start_ts)
)
ENGINE = MergeTree()
PARTITION BY toYYYYMMDD(event_date)
ORDER BY (event_date, conversation_id)
TTL event_date + INTERVAL 90 DAY
SETTINGS index_granularity = 8192;


CREATE TABLE trace_span (
    span_id           String,
    conversation_id   String,
    trace_id          String,
    parent_span_id    String DEFAULT '',
    span_type         LowCardinality(String),
    name              String,
    source            LowCardinality(String),
    start_ts          DateTime64(3),
    end_ts            DateTime64(3),
    duration_ms       UInt32,
    status            LowCardinality(String),
    error_message     String DEFAULT '',
    input_json        String DEFAULT '{}',
    output_json       String DEFAULT '{}',
    model             LowCardinality(String) DEFAULT '',
    token_input       UInt32 DEFAULT 0,
    token_output      UInt32 DEFAULT 0,
    cost              Decimal(10,6) DEFAULT 0,
    depth             UInt16 DEFAULT 0,
    sibling_index     UInt16 DEFAULT 0,
    event_date        Date DEFAULT toDate(start_ts)
)
ENGINE = MergeTree()
PARTITION BY toYYYYMMDD(event_date)
ORDER BY (event_date, conversation_id, start_ts)
TTL event_date + INTERVAL 90 DAY
SETTINGS index_granularity = 8192;


CREATE TABLE trace_event (
    event_id          String,
    conversation_id   String,
    trace_id          String,
    span_id           String DEFAULT '',
    correlation_id    String DEFAULT '',
    event_type        LowCardinality(String),
    event_name        String,
    source            LowCardinality(String),
    timestamp         DateTime64(3),
    duration_ms       UInt32 DEFAULT 0,
    payload_json      String DEFAULT '{}',
    event_date        Date DEFAULT toDate(timestamp)
)
ENGINE = MergeTree()
PARTITION BY toYYYYMMDD(event_date)
ORDER BY (event_date, conversation_id, timestamp)
TTL event_date + INTERVAL 90 DAY
SETTINGS index_granularity = 8192;

-- ============================================================================
-- ClickHouse does not support transactions; each CREATE TABLE above executes
-- independently. Re-running this migration requires manual DROP TABLE first
-- (Flyway will mark the migration as applied after execution).
-- ============================================================================
