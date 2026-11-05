-- Isolated-test schema matching the seven tables in the ClickHouse 20.12 migration.

CREATE TABLE default.trace_conversation
(
    `conversation_id` String COMMENT '会话唯一标识',
    `trace_id` String COMMENT '链路追踪标识',
    `user_id` String COMMENT '用户标识',
    `source` LowCardinality(String) COMMENT '会话来源或智能体名称',
    `agent_id` String COMMENT '智能体标识',
    `agent_name` String COMMENT '智能体名称',
    `start_ts` DateTime COMMENT '开始时间，秒级精度',
    `end_ts` DateTime COMMENT '结束时间，秒级精度',
    `duration_ms` UInt32 COMMENT '执行耗时，单位毫秒，独立记录以保留毫秒精度',
    `status` LowCardinality(String) COMMENT '执行状态',
    `error_message` String DEFAULT '' COMMENT '错误信息',
    `event_count` UInt32 DEFAULT 0 COMMENT '事件数量',
    `token_input` UInt32 DEFAULT 0 COMMENT '输入 Token 数量',
    `token_output` UInt32 DEFAULT 0 COMMENT '输出 Token 数量',
    `model` LowCardinality(String) DEFAULT '' COMMENT '模型名称',
    `event_date` Date DEFAULT toDate(start_ts) COMMENT '数据日期，用于分区及 90 天过期清理',
    `environment` String DEFAULT '' COMMENT '来源环境：TEST 为测试，PROD 为生产；历史未知环境为空'
)
ENGINE = MergeTree()
PARTITION BY toYYYYMMDD(event_date)
ORDER BY (event_date, conversation_id)
SETTINGS index_granularity = 8192;

CREATE TABLE default.trace_event
(
    `event_id` String COMMENT '事件唯一标识',
    `conversation_id` String COMMENT '会话唯一标识',
    `trace_id` String COMMENT '链路追踪标识',
    `event_type` LowCardinality(String) COMMENT '事件类型',
    `event_name` String COMMENT '事件名称',
    `source` String DEFAULT '' COMMENT '事件来源或智能体名称',
    `timestamp` DateTime COMMENT '事件发生时间，秒级精度',
    `duration_ms` UInt32 DEFAULT 0 COMMENT '执行耗时，单位毫秒，独立记录以保留毫秒精度',
    `event_json` String COMMENT '事件原始数据，JSON 字符串',
    `event_date` Date DEFAULT toDate(timestamp) COMMENT '数据日期，用于分区及 90 天过期清理'
)
ENGINE = MergeTree()
PARTITION BY toYYYYMMDD(event_date)
ORDER BY (event_date, conversation_id, timestamp)
SETTINGS index_granularity = 8192;

CREATE TABLE default.long_task_node_execution_log
(
    `log_id` String COMMENT '日志唯一标识',
    `flow_execution_id` Int64 COMMENT '流程执行标识',
    `node_execution_id` Int64 COMMENT '节点执行标识',
    `attempt_id` Int64 COMMENT '执行尝试标识',
    `attempt_no` UInt32 COMMENT '执行尝试次数',
    `user_id` String COMMENT '用户标识',
    `flow_name` String COMMENT '流程名称',
    `node_key` String COMMENT '节点编码',
    `node_name` String COMMENT '节点名称',
    `script_id` String COMMENT '脚本标识',
    `execution_ip` String DEFAULT '' COMMENT '执行机器 IP',
    `status` LowCardinality(String) COMMENT '执行状态',
    `retryable` UInt8 COMMENT '是否允许重试',
    `error_code` String COMMENT '错误编码',
    `started_at` DateTime COMMENT '开始时间，秒级精度',
    `completed_at` DateTime COMMENT '完成时间，秒级精度',
    `duration_ms` UInt64 COMMENT '执行耗时，单位毫秒，独立记录以保留毫秒精度',
    `params_json` String COMMENT '执行参数，JSON 字符串',
    `output_text` String COMMENT '执行输出内容',
    `error_message` String COMMENT '错误信息',
    `params_truncated` UInt8 COMMENT '参数是否被截断',
    `output_truncated` UInt8 COMMENT '输出是否被截断',
    `error_truncated` UInt8 COMMENT '错误内容是否被截断',
    `params_bytes` UInt64 COMMENT '原始参数字节数',
    `output_bytes` UInt64 COMMENT '原始输出字节数',
    `error_bytes` UInt64 COMMENT '原始错误字节数',
    `event_date` Date DEFAULT toDate(started_at) COMMENT '数据日期，用于分区及 90 天过期清理',
    `environment` String DEFAULT '' COMMENT '来源环境：TEST 为测试，PROD 为生产；历史未知环境为空'
)
ENGINE = MergeTree
PARTITION BY toYYYYMMDD(event_date)
ORDER BY (event_date, user_id, started_at, flow_execution_id, node_execution_id, attempt_no);

CREATE TABLE default.skill_flow_notification_link_click
(
    `event_id` UUID DEFAULT generateUUIDv4() COMMENT '事件唯一标识',
    `access_token` String DEFAULT '' COMMENT '报告访问令牌',
    `environment` LowCardinality(String) COMMENT '来源环境：TEST 为测试，PROD 为生产；历史未知环境为空',
    `notification_id` Nullable(Int64) COMMENT '通知标识',
    `flow_execution_id` Nullable(Int64) COMMENT '流程执行标识',
    `job_execution_id` Nullable(Int64) COMMENT '任务执行标识',
    `actor_user_id` String DEFAULT '' COMMENT '访问用户标识',
    `source` LowCardinality(String) COMMENT '访问来源',
    `event_type` LowCardinality(String) COMMENT '事件类型',
    `logged_in` UInt8 COMMENT '访问时是否已登录',
    `event_at` DateTime COMMENT '访问发生时间，秒级精度',
    `client_ip` Nullable(String) COMMENT '客户端 IP',
    `user_agent` Nullable(String) COMMENT '客户端 User-Agent',
    `task_display_name` Nullable(String) COMMENT '任务展示名称',
    `execution_at` Nullable(DateTime) COMMENT '任务执行时间，秒级精度',
    `created_at` DateTime DEFAULT now() COMMENT '记录创建时间，秒级精度'
)
ENGINE = MergeTree
PARTITION BY (environment, toYYYYMM(event_at))
ORDER BY (environment, event_at, source, event_type, actor_user_id);

CREATE TABLE default.digital_qa_resource_change_event
(
    `event_id` UUID COMMENT '事件唯一标识',
    `event_no` String COMMENT '资源变更事件编号',
    `resource_type` LowCardinality(String) COMMENT '资源类型',
    `resource_id` String COMMENT '资源标识',
    `resource_name` String COMMENT '资源名称',
    `operation_type` LowCardinality(String) COMMENT '操作类型',
    `operation_summary` String DEFAULT '' COMMENT '操作摘要',
    `operator_id` String COMMENT '操作人标识',
    `operator_name` String COMMENT '操作人名称',
    `operator_type` LowCardinality(String) DEFAULT 'user' COMMENT '操作主体类型，默认 user',
    `source` LowCardinality(String) DEFAULT 'ui' COMMENT '操作来源，默认 ui',
    `result` LowCardinality(String) DEFAULT 'success' COMMENT '操作结果，默认 success',
    `error_code` String DEFAULT '' COMMENT '错误编码',
    `error_message` String DEFAULT '' COMMENT '错误信息',
    `request_id` String DEFAULT '' COMMENT '请求链路标识',
    `client_ip` String DEFAULT '' COMMENT '客户端 IP',
    `user_agent` String DEFAULT '' COMMENT '客户端 User-Agent',
    `before_version` String DEFAULT '' COMMENT '变更前版本',
    `after_version` String DEFAULT '' COMMENT '变更后版本',
    `before_hash` String DEFAULT '' COMMENT '变更前内容哈希',
    `after_hash` String DEFAULT '' COMMENT '变更后内容哈希',
    `detail_count` UInt32 DEFAULT 0 COMMENT '变更明细数量',
    `created_at` DateTime('Asia/Shanghai') COMMENT '记录创建时间，秒级精度',
    `extra_data` String DEFAULT '' COMMENT '扩展数据',
    `environment` LowCardinality(String) DEFAULT '' COMMENT '来源环境：TEST 为测试，PROD 为生产；历史未知环境为空'
)
ENGINE = MergeTree
PARTITION BY toYYYYMM(created_at)
ORDER BY (resource_type, resource_id, created_at, event_id);

CREATE TABLE default.digital_qa_resource_change_detail
(
    `event_id` UUID COMMENT '事件唯一标识',
    `detail_no` UInt32 COMMENT '变更明细序号',
    `resource_type` LowCardinality(String) COMMENT '资源类型',
    `resource_id` String COMMENT '资源标识',
    `target_type` LowCardinality(String) COMMENT '变更目标类型',
    `target_id` String DEFAULT '' COMMENT '变更目标标识',
    `target_name` String DEFAULT '' COMMENT '变更目标名称',
    `field_name` String DEFAULT '' COMMENT '字段名称',
    `field_label` String DEFAULT '' COMMENT '字段展示名称',
    `change_type` LowCardinality(String) COMMENT '变更类型',
    `before_value` String DEFAULT '' COMMENT '变更前原始值',
    `after_value` String DEFAULT '' COMMENT '变更后原始值',
    `before_value_json` String DEFAULT '' COMMENT '变更前值，JSON 字符串',
    `after_value_json` String DEFAULT '' COMMENT '变更后值，JSON 字符串',
    `display_before_value` String DEFAULT '' COMMENT '变更前展示值',
    `display_after_value` String DEFAULT '' COMMENT '变更后展示值',
    `value_type` LowCardinality(String) DEFAULT 'string' COMMENT '字段值类型',
    `is_sensitive` UInt8 DEFAULT 0 COMMENT '是否为敏感字段',
    `is_truncated` UInt8 DEFAULT 0 COMMENT '字段值是否被截断',
    `sort_order` UInt32 DEFAULT 0 COMMENT '明细展示顺序',
    `created_at` DateTime('Asia/Shanghai') COMMENT '记录创建时间，秒级精度'
)
ENGINE = MergeTree
PARTITION BY toYYYYMM(created_at)
ORDER BY (event_id, detail_no);

CREATE TABLE default.platform_orders
(
    `order_id` UInt64 COMMENT '订单标识',
    `user_id` UInt64 COMMENT '用户标识',
    `product` String COMMENT '产品名称',
    `amount` Float64 COMMENT '订单金额',
    `order_date` Date COMMENT '订单日期'
)
ENGINE = MergeTree()
ORDER BY order_id
SETTINGS index_granularity = 8192;