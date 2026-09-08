create table platform_orders
(
    order_id   UInt64,
    user_id    UInt64,
    product    String,
    amount     Float64,
    order_date Date
)
    engine = MergeTree ORDER BY order_id
        SETTINGS index_granularity = 8192;

create table trace_conversation
(
    conversation_id String,
    trace_id        String,
    user_id         String,
    source          LowCardinality(String),
    agent_id        String,
    agent_name      String,
    start_ts        DateTime64(3),
    end_ts          DateTime64(3),
    duration_ms     UInt32,
    status          LowCardinality(String),
    error_message   String                 default '',
    event_count     UInt32                 default 0,
    token_input     UInt32                 default 0,
    token_output    UInt32                 default 0,
    model           LowCardinality(String) default '',
    event_date      Date                   default toDate(start_ts)
)
    engine = MergeTree PARTITION BY toYYYYMMDD(event_date)
        ORDER BY (event_date, conversation_id)
        TTL event_date + toIntervalDay(90)
        SETTINGS index_granularity = 8192;

create table trace_event
(
    event_id        String,
    conversation_id String,
    trace_id        String,
    event_type      LowCardinality(String),
    event_name      String,
    source          String default '',
    timestamp       DateTime64(3),
    duration_ms     UInt32 default 0,
    event_json      String,
    event_date      Date   default toDate(timestamp)
)
    engine = MergeTree PARTITION BY toYYYYMMDD(event_date)
        ORDER BY (event_date, conversation_id, timestamp)
        TTL event_date + toIntervalDay(90)
        SETTINGS index_granularity = 8192;

