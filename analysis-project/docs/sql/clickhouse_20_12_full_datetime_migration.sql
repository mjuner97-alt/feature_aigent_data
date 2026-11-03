-- ClickHouse 20.12.3.3 / clickhouse-jdbc 0.2.4 完整停写迁移（人工分阶段执行）。
-- 这是手动运维脚本，不放入 Flyway 自动迁移路径，不要与原 .2 ALTER 脚本同时执行。
-- 所有表统一使用 default 库；若业务连接使用其他库，执行前统一替换 default.。
-- 依据仓库最终表结构生成，含 environment / execution_ip / access_token。
-- 适用：以下七张源表均已存在，且为本地普通 MergeTree 表，无额外字段、物化视图或分布式表依赖。
-- 若源表使用 ReplicatedMergeTree、额外索引/投影/Codec/自定义设置，先按 SHOW CREATE TABLE 调整新表。
-- 需要空间容纳原表和新表；切换前后始终停写并暂停相关读取，直到全部七张表切换成功。
-- 开始前停止业务、定时任务、消费者，排空所有异步写入；处理完已有 mutation 再执行。
-- DateTime64 -> DateTime 会舍弃小数秒；duration_ms 原样保留。新表不带 TTL，先完整复制。
-- 转换前确认历史时间落在 DateTime 可表示范围内，且时间列非 Nullable（execution_at 除外）。
-- 普通 DateTime 沿用服务端时区；审计 created_at 明确使用 Asia/Shanghai。
-- 不删除任何源表/备份表，不使用 SELECT * 复制。恢复写入后回滚必须先处理新增数据。
-- JDBC 必须每次 execute 一条语句，执行器遇错立即停止；禁止忽略错误继续。
-- CREATE 不带 IF NOT EXISTS，防止重复执行复用旧迁移表并重复 INSERT。
-- 任一步失败后按实际表名和行数恢复，禁止从头重跑 INSERT（MergeTree 不会自动去重）。

-- 阶段 0：只读检查。确认版本、数据库引擎、七张表实际定义及依赖。
SELECT version(), timezone();
SELECT name, engine FROM system.databases WHERE name = 'default';
SELECT database, name, engine, create_table_query FROM system.tables
WHERE database = 'default' AND name IN (
    'trace_conversation', 'trace_event', 'long_task_node_execution_log',
    'skill_flow_notification_link_click', 'digital_qa_resource_change_event',
    'digital_qa_resource_change_detail', 'platform_orders');
-- 检查上方只有预期结构，无额外字段或依赖，再继续。
SELECT database, table, mutation_id, command, is_done FROM system.mutations
WHERE database = 'default' AND is_done = 0;

-- 阶段 1：停写后冻结源表合并，避免 TTL 在复制和校验时删除历史行。
SYSTEM STOP MERGES default.trace_conversation;
SYSTEM STOP MERGES default.trace_event;
SYSTEM STOP MERGES default.long_task_node_execution_log;
SYSTEM STOP MERGES default.skill_flow_notification_link_click;
SYSTEM STOP MERGES default.digital_qa_resource_change_event;
SYSTEM STOP MERGES default.digital_qa_resource_change_detail;
SYSTEM STOP MERGES default.platform_orders;
-- 等下面查询返回 0 行再继续；STOP MERGES 不保证已运行的合并瞬间结束。
SELECT database, table, elapsed FROM system.merges WHERE database = 'default';

-- 阶段 2：补齐已知增量字段（保留已有值，只对缺失列补默认空字符串）。
ALTER TABLE default.trace_conversation ADD COLUMN IF NOT EXISTS environment String DEFAULT '';
ALTER TABLE default.long_task_node_execution_log ADD COLUMN IF NOT EXISTS environment String DEFAULT '';
ALTER TABLE default.long_task_node_execution_log ADD COLUMN IF NOT EXISTS execution_ip String DEFAULT '';
ALTER TABLE default.skill_flow_notification_link_click ADD COLUMN IF NOT EXISTS access_token String DEFAULT '';
ALTER TABLE default.digital_qa_resource_change_event ADD COLUMN IF NOT EXISTS environment LowCardinality(String) DEFAULT '';

-- 阶段 3：创建全部新表，无 TTL，避免历史数据在校验前过期。
CREATE TABLE default.trace_conversation__mig_20261103
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

CREATE TABLE default.trace_event__mig_20261103
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

CREATE TABLE default.long_task_node_execution_log__mig_20261103
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

CREATE TABLE default.skill_flow_notification_link_click__mig_20261103
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

CREATE TABLE default.digital_qa_resource_change_event__mig_20261103
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

CREATE TABLE default.digital_qa_resource_change_detail__mig_20261103
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

CREATE TABLE default.platform_orders__mig_20261103
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

-- 阶段 4：复制一次。所有列显式转换成目标类型；历史日期和业务字段不重新计算。
INSERT INTO default.trace_conversation__mig_20261103 (`conversation_id`, `trace_id`, `user_id`, `source`, `agent_id`, `agent_name`, `start_ts`, `end_ts`, `duration_ms`, `status`, `error_message`, `event_count`, `token_input`, `token_output`, `model`, `event_date`, `environment`)
SELECT
    CAST(`conversation_id` AS String) AS `conversation_id`,
    CAST(`trace_id` AS String) AS `trace_id`,
    CAST(`user_id` AS String) AS `user_id`,
    CAST(`source` AS LowCardinality(String)) AS `source`,
    CAST(`agent_id` AS String) AS `agent_id`,
    CAST(`agent_name` AS String) AS `agent_name`,
    CAST(`start_ts` AS DateTime) AS `start_ts`,
    CAST(`end_ts` AS DateTime) AS `end_ts`,
    CAST(`duration_ms` AS UInt32) AS `duration_ms`,
    CAST(`status` AS LowCardinality(String)) AS `status`,
    CAST(`error_message` AS String) AS `error_message`,
    CAST(`event_count` AS UInt32) AS `event_count`,
    CAST(`token_input` AS UInt32) AS `token_input`,
    CAST(`token_output` AS UInt32) AS `token_output`,
    CAST(`model` AS LowCardinality(String)) AS `model`,
    CAST(`event_date` AS Date) AS `event_date`,
    CAST(`environment` AS String) AS `environment`
FROM default.trace_conversation;

INSERT INTO default.trace_event__mig_20261103 (`event_id`, `conversation_id`, `trace_id`, `event_type`, `event_name`, `source`, `timestamp`, `duration_ms`, `event_json`, `event_date`)
SELECT
    CAST(`event_id` AS String) AS `event_id`,
    CAST(`conversation_id` AS String) AS `conversation_id`,
    CAST(`trace_id` AS String) AS `trace_id`,
    CAST(`event_type` AS LowCardinality(String)) AS `event_type`,
    CAST(`event_name` AS String) AS `event_name`,
    CAST(`source` AS String) AS `source`,
    CAST(`timestamp` AS DateTime) AS `timestamp`,
    CAST(`duration_ms` AS UInt32) AS `duration_ms`,
    CAST(`event_json` AS String) AS `event_json`,
    CAST(`event_date` AS Date) AS `event_date`
FROM default.trace_event;

INSERT INTO default.long_task_node_execution_log__mig_20261103 (`log_id`, `flow_execution_id`, `node_execution_id`, `attempt_id`, `attempt_no`, `user_id`, `flow_name`, `node_key`, `node_name`, `script_id`, `execution_ip`, `status`, `retryable`, `error_code`, `started_at`, `completed_at`, `duration_ms`, `params_json`, `output_text`, `error_message`, `params_truncated`, `output_truncated`, `error_truncated`, `params_bytes`, `output_bytes`, `error_bytes`, `event_date`, `environment`)
SELECT
    CAST(`log_id` AS String) AS `log_id`,
    CAST(`flow_execution_id` AS Int64) AS `flow_execution_id`,
    CAST(`node_execution_id` AS Int64) AS `node_execution_id`,
    CAST(`attempt_id` AS Int64) AS `attempt_id`,
    CAST(`attempt_no` AS UInt32) AS `attempt_no`,
    CAST(`user_id` AS String) AS `user_id`,
    CAST(`flow_name` AS String) AS `flow_name`,
    CAST(`node_key` AS String) AS `node_key`,
    CAST(`node_name` AS String) AS `node_name`,
    CAST(`script_id` AS String) AS `script_id`,
    CAST(`execution_ip` AS String) AS `execution_ip`,
    CAST(`status` AS LowCardinality(String)) AS `status`,
    CAST(`retryable` AS UInt8) AS `retryable`,
    CAST(`error_code` AS String) AS `error_code`,
    CAST(`started_at` AS DateTime) AS `started_at`,
    CAST(`completed_at` AS DateTime) AS `completed_at`,
    CAST(`duration_ms` AS UInt64) AS `duration_ms`,
    CAST(`params_json` AS String) AS `params_json`,
    CAST(`output_text` AS String) AS `output_text`,
    CAST(`error_message` AS String) AS `error_message`,
    CAST(`params_truncated` AS UInt8) AS `params_truncated`,
    CAST(`output_truncated` AS UInt8) AS `output_truncated`,
    CAST(`error_truncated` AS UInt8) AS `error_truncated`,
    CAST(`params_bytes` AS UInt64) AS `params_bytes`,
    CAST(`output_bytes` AS UInt64) AS `output_bytes`,
    CAST(`error_bytes` AS UInt64) AS `error_bytes`,
    CAST(`event_date` AS Date) AS `event_date`,
    CAST(`environment` AS String) AS `environment`
FROM default.long_task_node_execution_log;

INSERT INTO default.skill_flow_notification_link_click__mig_20261103 (`event_id`, `access_token`, `environment`, `notification_id`, `flow_execution_id`, `job_execution_id`, `actor_user_id`, `source`, `event_type`, `logged_in`, `event_at`, `client_ip`, `user_agent`, `task_display_name`, `execution_at`, `created_at`)
SELECT
    CAST(`event_id` AS UUID) AS `event_id`,
    CAST(`access_token` AS String) AS `access_token`,
    CAST(`environment` AS LowCardinality(String)) AS `environment`,
    CAST(`notification_id` AS Nullable(Int64)) AS `notification_id`,
    CAST(`flow_execution_id` AS Nullable(Int64)) AS `flow_execution_id`,
    CAST(`job_execution_id` AS Nullable(Int64)) AS `job_execution_id`,
    CAST(`actor_user_id` AS String) AS `actor_user_id`,
    CAST(`source` AS LowCardinality(String)) AS `source`,
    CAST(`event_type` AS LowCardinality(String)) AS `event_type`,
    CAST(`logged_in` AS UInt8) AS `logged_in`,
    CAST(`event_at` AS DateTime) AS `event_at`,
    CAST(`client_ip` AS Nullable(String)) AS `client_ip`,
    CAST(`user_agent` AS Nullable(String)) AS `user_agent`,
    CAST(`task_display_name` AS Nullable(String)) AS `task_display_name`,
    CAST(`execution_at` AS Nullable(DateTime)) AS `execution_at`,
    CAST(`created_at` AS DateTime) AS `created_at`
FROM default.skill_flow_notification_link_click;

INSERT INTO default.digital_qa_resource_change_event__mig_20261103 (`event_id`, `event_no`, `resource_type`, `resource_id`, `resource_name`, `operation_type`, `operation_summary`, `operator_id`, `operator_name`, `operator_type`, `source`, `result`, `error_code`, `error_message`, `request_id`, `client_ip`, `user_agent`, `before_version`, `after_version`, `before_hash`, `after_hash`, `detail_count`, `created_at`, `extra_data`, `environment`)
SELECT
    CAST(`event_id` AS UUID) AS `event_id`,
    CAST(`event_no` AS String) AS `event_no`,
    CAST(`resource_type` AS LowCardinality(String)) AS `resource_type`,
    CAST(`resource_id` AS String) AS `resource_id`,
    CAST(`resource_name` AS String) AS `resource_name`,
    CAST(`operation_type` AS LowCardinality(String)) AS `operation_type`,
    CAST(`operation_summary` AS String) AS `operation_summary`,
    CAST(`operator_id` AS String) AS `operator_id`,
    CAST(`operator_name` AS String) AS `operator_name`,
    CAST(`operator_type` AS LowCardinality(String)) AS `operator_type`,
    CAST(`source` AS LowCardinality(String)) AS `source`,
    CAST(`result` AS LowCardinality(String)) AS `result`,
    CAST(`error_code` AS String) AS `error_code`,
    CAST(`error_message` AS String) AS `error_message`,
    CAST(`request_id` AS String) AS `request_id`,
    CAST(`client_ip` AS String) AS `client_ip`,
    CAST(`user_agent` AS String) AS `user_agent`,
    CAST(`before_version` AS String) AS `before_version`,
    CAST(`after_version` AS String) AS `after_version`,
    CAST(`before_hash` AS String) AS `before_hash`,
    CAST(`after_hash` AS String) AS `after_hash`,
    CAST(`detail_count` AS UInt32) AS `detail_count`,
    CAST(`created_at` AS DateTime('Asia/Shanghai')) AS `created_at`,
    CAST(`extra_data` AS String) AS `extra_data`,
    CAST(`environment` AS LowCardinality(String)) AS `environment`
FROM default.digital_qa_resource_change_event;

INSERT INTO default.digital_qa_resource_change_detail__mig_20261103 (`event_id`, `detail_no`, `resource_type`, `resource_id`, `target_type`, `target_id`, `target_name`, `field_name`, `field_label`, `change_type`, `before_value`, `after_value`, `before_value_json`, `after_value_json`, `display_before_value`, `display_after_value`, `value_type`, `is_sensitive`, `is_truncated`, `sort_order`, `created_at`)
SELECT
    CAST(`event_id` AS UUID) AS `event_id`,
    CAST(`detail_no` AS UInt32) AS `detail_no`,
    CAST(`resource_type` AS LowCardinality(String)) AS `resource_type`,
    CAST(`resource_id` AS String) AS `resource_id`,
    CAST(`target_type` AS LowCardinality(String)) AS `target_type`,
    CAST(`target_id` AS String) AS `target_id`,
    CAST(`target_name` AS String) AS `target_name`,
    CAST(`field_name` AS String) AS `field_name`,
    CAST(`field_label` AS String) AS `field_label`,
    CAST(`change_type` AS LowCardinality(String)) AS `change_type`,
    CAST(`before_value` AS String) AS `before_value`,
    CAST(`after_value` AS String) AS `after_value`,
    CAST(`before_value_json` AS String) AS `before_value_json`,
    CAST(`after_value_json` AS String) AS `after_value_json`,
    CAST(`display_before_value` AS String) AS `display_before_value`,
    CAST(`display_after_value` AS String) AS `display_after_value`,
    CAST(`value_type` AS LowCardinality(String)) AS `value_type`,
    CAST(`is_sensitive` AS UInt8) AS `is_sensitive`,
    CAST(`is_truncated` AS UInt8) AS `is_truncated`,
    CAST(`sort_order` AS UInt32) AS `sort_order`,
    CAST(`created_at` AS DateTime('Asia/Shanghai')) AS `created_at`
FROM default.digital_qa_resource_change_detail;

INSERT INTO default.platform_orders__mig_20261103 (`order_id`, `user_id`, `product`, `amount`, `order_date`)
SELECT
    CAST(`order_id` AS UInt64) AS `order_id`,
    CAST(`user_id` AS UInt64) AS `user_id`,
    CAST(`product` AS String) AS `product`,
    CAST(`amount` AS Float64) AS `amount`,
    CAST(`order_date` AS Date) AS `order_date`
FROM default.platform_orders;

-- 阶段 5：校验。每张表的源/目标行数必须相同，mismatch_groups 必须为 0。
-- 全列分组比较保留重复行数量，可发现漏行、重复复制和字段不一致；大表可能消耗较多内存。
-- 源列先转换到目标类型比较，因此秒级转换产生的精度损失是预期行为。
-- 任意查询报错或 mismatch_groups 非零：停止，不得执行阶段 6。
SELECT 'trace_conversation' AS table_name,
    (SELECT count() FROM default.trace_conversation) AS source_rows,
    (SELECT count() FROM default.trace_conversation__mig_20261103) AS target_rows;
SELECT 'trace_conversation' AS table_name, count() AS mismatch_groups
FROM
(
    SELECT row_value FROM
    (
        SELECT tuple(CAST(`conversation_id` AS String), CAST(`trace_id` AS String), CAST(`user_id` AS String), CAST(`source` AS LowCardinality(String)), CAST(`agent_id` AS String), CAST(`agent_name` AS String), CAST(`start_ts` AS DateTime), CAST(`end_ts` AS DateTime), CAST(`duration_ms` AS UInt32), CAST(`status` AS LowCardinality(String)), CAST(`error_message` AS String), CAST(`event_count` AS UInt32), CAST(`token_input` AS UInt32), CAST(`token_output` AS UInt32), CAST(`model` AS LowCardinality(String)), CAST(`event_date` AS Date), CAST(`environment` AS String)) AS row_value, toInt64(1) AS balance
        FROM default.trace_conversation
        UNION ALL
        SELECT tuple(CAST(`conversation_id` AS String), CAST(`trace_id` AS String), CAST(`user_id` AS String), CAST(`source` AS LowCardinality(String)), CAST(`agent_id` AS String), CAST(`agent_name` AS String), CAST(`start_ts` AS DateTime), CAST(`end_ts` AS DateTime), CAST(`duration_ms` AS UInt32), CAST(`status` AS LowCardinality(String)), CAST(`error_message` AS String), CAST(`event_count` AS UInt32), CAST(`token_input` AS UInt32), CAST(`token_output` AS UInt32), CAST(`model` AS LowCardinality(String)), CAST(`event_date` AS Date), CAST(`environment` AS String)) AS row_value, toInt64(-1) AS balance
        FROM default.trace_conversation__mig_20261103
    )
    GROUP BY row_value
    HAVING sum(balance) != 0
);

SELECT 'trace_event' AS table_name,
    (SELECT count() FROM default.trace_event) AS source_rows,
    (SELECT count() FROM default.trace_event__mig_20261103) AS target_rows;
SELECT 'trace_event' AS table_name, count() AS mismatch_groups
FROM
(
    SELECT row_value FROM
    (
        SELECT tuple(CAST(`event_id` AS String), CAST(`conversation_id` AS String), CAST(`trace_id` AS String), CAST(`event_type` AS LowCardinality(String)), CAST(`event_name` AS String), CAST(`source` AS String), CAST(`timestamp` AS DateTime), CAST(`duration_ms` AS UInt32), CAST(`event_json` AS String), CAST(`event_date` AS Date)) AS row_value, toInt64(1) AS balance
        FROM default.trace_event
        UNION ALL
        SELECT tuple(CAST(`event_id` AS String), CAST(`conversation_id` AS String), CAST(`trace_id` AS String), CAST(`event_type` AS LowCardinality(String)), CAST(`event_name` AS String), CAST(`source` AS String), CAST(`timestamp` AS DateTime), CAST(`duration_ms` AS UInt32), CAST(`event_json` AS String), CAST(`event_date` AS Date)) AS row_value, toInt64(-1) AS balance
        FROM default.trace_event__mig_20261103
    )
    GROUP BY row_value
    HAVING sum(balance) != 0
);

SELECT 'long_task_node_execution_log' AS table_name,
    (SELECT count() FROM default.long_task_node_execution_log) AS source_rows,
    (SELECT count() FROM default.long_task_node_execution_log__mig_20261103) AS target_rows;
SELECT 'long_task_node_execution_log' AS table_name, count() AS mismatch_groups
FROM
(
    SELECT row_value FROM
    (
        SELECT tuple(CAST(`log_id` AS String), CAST(`flow_execution_id` AS Int64), CAST(`node_execution_id` AS Int64), CAST(`attempt_id` AS Int64), CAST(`attempt_no` AS UInt32), CAST(`user_id` AS String), CAST(`flow_name` AS String), CAST(`node_key` AS String), CAST(`node_name` AS String), CAST(`script_id` AS String), CAST(`execution_ip` AS String), CAST(`status` AS LowCardinality(String)), CAST(`retryable` AS UInt8), CAST(`error_code` AS String), CAST(`started_at` AS DateTime), CAST(`completed_at` AS DateTime), CAST(`duration_ms` AS UInt64), CAST(`params_json` AS String), CAST(`output_text` AS String), CAST(`error_message` AS String), CAST(`params_truncated` AS UInt8), CAST(`output_truncated` AS UInt8), CAST(`error_truncated` AS UInt8), CAST(`params_bytes` AS UInt64), CAST(`output_bytes` AS UInt64), CAST(`error_bytes` AS UInt64), CAST(`event_date` AS Date), CAST(`environment` AS String)) AS row_value, toInt64(1) AS balance
        FROM default.long_task_node_execution_log
        UNION ALL
        SELECT tuple(CAST(`log_id` AS String), CAST(`flow_execution_id` AS Int64), CAST(`node_execution_id` AS Int64), CAST(`attempt_id` AS Int64), CAST(`attempt_no` AS UInt32), CAST(`user_id` AS String), CAST(`flow_name` AS String), CAST(`node_key` AS String), CAST(`node_name` AS String), CAST(`script_id` AS String), CAST(`execution_ip` AS String), CAST(`status` AS LowCardinality(String)), CAST(`retryable` AS UInt8), CAST(`error_code` AS String), CAST(`started_at` AS DateTime), CAST(`completed_at` AS DateTime), CAST(`duration_ms` AS UInt64), CAST(`params_json` AS String), CAST(`output_text` AS String), CAST(`error_message` AS String), CAST(`params_truncated` AS UInt8), CAST(`output_truncated` AS UInt8), CAST(`error_truncated` AS UInt8), CAST(`params_bytes` AS UInt64), CAST(`output_bytes` AS UInt64), CAST(`error_bytes` AS UInt64), CAST(`event_date` AS Date), CAST(`environment` AS String)) AS row_value, toInt64(-1) AS balance
        FROM default.long_task_node_execution_log__mig_20261103
    )
    GROUP BY row_value
    HAVING sum(balance) != 0
);

SELECT 'skill_flow_notification_link_click' AS table_name,
    (SELECT count() FROM default.skill_flow_notification_link_click) AS source_rows,
    (SELECT count() FROM default.skill_flow_notification_link_click__mig_20261103) AS target_rows;
SELECT 'skill_flow_notification_link_click' AS table_name, count() AS mismatch_groups
FROM
(
    SELECT row_value FROM
    (
        SELECT tuple(CAST(`event_id` AS UUID), CAST(`access_token` AS String), CAST(`environment` AS LowCardinality(String)), CAST(`notification_id` AS Nullable(Int64)), CAST(`flow_execution_id` AS Nullable(Int64)), CAST(`job_execution_id` AS Nullable(Int64)), CAST(`actor_user_id` AS String), CAST(`source` AS LowCardinality(String)), CAST(`event_type` AS LowCardinality(String)), CAST(`logged_in` AS UInt8), CAST(`event_at` AS DateTime), CAST(`client_ip` AS Nullable(String)), CAST(`user_agent` AS Nullable(String)), CAST(`task_display_name` AS Nullable(String)), CAST(`execution_at` AS Nullable(DateTime)), CAST(`created_at` AS DateTime)) AS row_value, toInt64(1) AS balance
        FROM default.skill_flow_notification_link_click
        UNION ALL
        SELECT tuple(CAST(`event_id` AS UUID), CAST(`access_token` AS String), CAST(`environment` AS LowCardinality(String)), CAST(`notification_id` AS Nullable(Int64)), CAST(`flow_execution_id` AS Nullable(Int64)), CAST(`job_execution_id` AS Nullable(Int64)), CAST(`actor_user_id` AS String), CAST(`source` AS LowCardinality(String)), CAST(`event_type` AS LowCardinality(String)), CAST(`logged_in` AS UInt8), CAST(`event_at` AS DateTime), CAST(`client_ip` AS Nullable(String)), CAST(`user_agent` AS Nullable(String)), CAST(`task_display_name` AS Nullable(String)), CAST(`execution_at` AS Nullable(DateTime)), CAST(`created_at` AS DateTime)) AS row_value, toInt64(-1) AS balance
        FROM default.skill_flow_notification_link_click__mig_20261103
    )
    GROUP BY row_value
    HAVING sum(balance) != 0
);

SELECT 'digital_qa_resource_change_event' AS table_name,
    (SELECT count() FROM default.digital_qa_resource_change_event) AS source_rows,
    (SELECT count() FROM default.digital_qa_resource_change_event__mig_20261103) AS target_rows;
SELECT 'digital_qa_resource_change_event' AS table_name, count() AS mismatch_groups
FROM
(
    SELECT row_value FROM
    (
        SELECT tuple(CAST(`event_id` AS UUID), CAST(`event_no` AS String), CAST(`resource_type` AS LowCardinality(String)), CAST(`resource_id` AS String), CAST(`resource_name` AS String), CAST(`operation_type` AS LowCardinality(String)), CAST(`operation_summary` AS String), CAST(`operator_id` AS String), CAST(`operator_name` AS String), CAST(`operator_type` AS LowCardinality(String)), CAST(`source` AS LowCardinality(String)), CAST(`result` AS LowCardinality(String)), CAST(`error_code` AS String), CAST(`error_message` AS String), CAST(`request_id` AS String), CAST(`client_ip` AS String), CAST(`user_agent` AS String), CAST(`before_version` AS String), CAST(`after_version` AS String), CAST(`before_hash` AS String), CAST(`after_hash` AS String), CAST(`detail_count` AS UInt32), CAST(`created_at` AS DateTime('Asia/Shanghai')), CAST(`extra_data` AS String), CAST(`environment` AS LowCardinality(String))) AS row_value, toInt64(1) AS balance
        FROM default.digital_qa_resource_change_event
        UNION ALL
        SELECT tuple(CAST(`event_id` AS UUID), CAST(`event_no` AS String), CAST(`resource_type` AS LowCardinality(String)), CAST(`resource_id` AS String), CAST(`resource_name` AS String), CAST(`operation_type` AS LowCardinality(String)), CAST(`operation_summary` AS String), CAST(`operator_id` AS String), CAST(`operator_name` AS String), CAST(`operator_type` AS LowCardinality(String)), CAST(`source` AS LowCardinality(String)), CAST(`result` AS LowCardinality(String)), CAST(`error_code` AS String), CAST(`error_message` AS String), CAST(`request_id` AS String), CAST(`client_ip` AS String), CAST(`user_agent` AS String), CAST(`before_version` AS String), CAST(`after_version` AS String), CAST(`before_hash` AS String), CAST(`after_hash` AS String), CAST(`detail_count` AS UInt32), CAST(`created_at` AS DateTime('Asia/Shanghai')), CAST(`extra_data` AS String), CAST(`environment` AS LowCardinality(String))) AS row_value, toInt64(-1) AS balance
        FROM default.digital_qa_resource_change_event__mig_20261103
    )
    GROUP BY row_value
    HAVING sum(balance) != 0
);

SELECT 'digital_qa_resource_change_detail' AS table_name,
    (SELECT count() FROM default.digital_qa_resource_change_detail) AS source_rows,
    (SELECT count() FROM default.digital_qa_resource_change_detail__mig_20261103) AS target_rows;
SELECT 'digital_qa_resource_change_detail' AS table_name, count() AS mismatch_groups
FROM
(
    SELECT row_value FROM
    (
        SELECT tuple(CAST(`event_id` AS UUID), CAST(`detail_no` AS UInt32), CAST(`resource_type` AS LowCardinality(String)), CAST(`resource_id` AS String), CAST(`target_type` AS LowCardinality(String)), CAST(`target_id` AS String), CAST(`target_name` AS String), CAST(`field_name` AS String), CAST(`field_label` AS String), CAST(`change_type` AS LowCardinality(String)), CAST(`before_value` AS String), CAST(`after_value` AS String), CAST(`before_value_json` AS String), CAST(`after_value_json` AS String), CAST(`display_before_value` AS String), CAST(`display_after_value` AS String), CAST(`value_type` AS LowCardinality(String)), CAST(`is_sensitive` AS UInt8), CAST(`is_truncated` AS UInt8), CAST(`sort_order` AS UInt32), CAST(`created_at` AS DateTime('Asia/Shanghai'))) AS row_value, toInt64(1) AS balance
        FROM default.digital_qa_resource_change_detail
        UNION ALL
        SELECT tuple(CAST(`event_id` AS UUID), CAST(`detail_no` AS UInt32), CAST(`resource_type` AS LowCardinality(String)), CAST(`resource_id` AS String), CAST(`target_type` AS LowCardinality(String)), CAST(`target_id` AS String), CAST(`target_name` AS String), CAST(`field_name` AS String), CAST(`field_label` AS String), CAST(`change_type` AS LowCardinality(String)), CAST(`before_value` AS String), CAST(`after_value` AS String), CAST(`before_value_json` AS String), CAST(`after_value_json` AS String), CAST(`display_before_value` AS String), CAST(`display_after_value` AS String), CAST(`value_type` AS LowCardinality(String)), CAST(`is_sensitive` AS UInt8), CAST(`is_truncated` AS UInt8), CAST(`sort_order` AS UInt32), CAST(`created_at` AS DateTime('Asia/Shanghai'))) AS row_value, toInt64(-1) AS balance
        FROM default.digital_qa_resource_change_detail__mig_20261103
    )
    GROUP BY row_value
    HAVING sum(balance) != 0
);

SELECT 'platform_orders' AS table_name,
    (SELECT count() FROM default.platform_orders) AS source_rows,
    (SELECT count() FROM default.platform_orders__mig_20261103) AS target_rows;
SELECT 'platform_orders' AS table_name, count() AS mismatch_groups
FROM
(
    SELECT row_value FROM
    (
        SELECT tuple(CAST(`order_id` AS UInt64), CAST(`user_id` AS UInt64), CAST(`product` AS String), CAST(`amount` AS Float64), CAST(`order_date` AS Date)) AS row_value, toInt64(1) AS balance
        FROM default.platform_orders
        UNION ALL
        SELECT tuple(CAST(`order_id` AS UInt64), CAST(`user_id` AS UInt64), CAST(`product` AS String), CAST(`amount` AS Float64), CAST(`order_date` AS Date)) AS row_value, toInt64(-1) AS balance
        FROM default.platform_orders__mig_20261103
    )
    GROUP BY row_value
    HAVING sum(balance) != 0
);

-- 阶段 6：仅阶段 5 全部通过后执行。继续保持停写/暂停读取。
-- 每张表单独切换；即使一个 RENAME 写两个表，也不是整体事务。
-- 若中间失败立即停止，检查 system.tables，按实际表名补齐，不能假设自动回滚。
RENAME TABLE default.trace_conversation TO default.trace_conversation__bak_20261103,
    default.trace_conversation__mig_20261103 TO default.trace_conversation;
RENAME TABLE default.trace_event TO default.trace_event__bak_20261103,
    default.trace_event__mig_20261103 TO default.trace_event;
RENAME TABLE default.long_task_node_execution_log TO default.long_task_node_execution_log__bak_20261103,
    default.long_task_node_execution_log__mig_20261103 TO default.long_task_node_execution_log;
RENAME TABLE default.skill_flow_notification_link_click TO default.skill_flow_notification_link_click__bak_20261103,
    default.skill_flow_notification_link_click__mig_20261103 TO default.skill_flow_notification_link_click;
RENAME TABLE default.digital_qa_resource_change_event TO default.digital_qa_resource_change_event__bak_20261103,
    default.digital_qa_resource_change_event__mig_20261103 TO default.digital_qa_resource_change_event;
RENAME TABLE default.digital_qa_resource_change_detail TO default.digital_qa_resource_change_detail__bak_20261103,
    default.digital_qa_resource_change_detail__mig_20261103 TO default.digital_qa_resource_change_detail;
RENAME TABLE default.platform_orders TO default.platform_orders__bak_20261103,
    default.platform_orders__mig_20261103 TO default.platform_orders;

-- 重新按备份名冻结合并，避免表名切换影响停止标记。
SYSTEM STOP MERGES default.trace_conversation__bak_20261103;
SYSTEM STOP MERGES default.trace_event__bak_20261103;
SYSTEM STOP MERGES default.long_task_node_execution_log__bak_20261103;
SYSTEM STOP MERGES default.skill_flow_notification_link_click__bak_20261103;
SYSTEM STOP MERGES default.digital_qa_resource_change_event__bak_20261103;
SYSTEM STOP MERGES default.digital_qa_resource_change_detail__bak_20261103;
SYSTEM STOP MERGES default.platform_orders__bak_20261103;

-- 阶段 7：全部切换成功后设置原保留周期。设置 TTL 后超期历史数据将被异步删除。
-- 备份表继续保持 STOP MERGES，防止其原有 TTL 清理备份；服务器重启后需重新 STOP MERGES。
-- 备份表不是独立备份介质，需要长期保留时另做外部备份。
ALTER TABLE default.trace_conversation MODIFY TTL event_date + INTERVAL 90 DAY;
ALTER TABLE default.trace_event MODIFY TTL event_date + INTERVAL 90 DAY;
ALTER TABLE default.long_task_node_execution_log MODIFY TTL event_date + INTERVAL 90 DAY;
ALTER TABLE default.skill_flow_notification_link_click MODIFY TTL event_at + INTERVAL 2 MONTH;
ALTER TABLE default.digital_qa_resource_change_event MODIFY TTL created_at + INTERVAL 2 MONTH;
ALTER TABLE default.digital_qa_resource_change_detail MODIFY TTL created_at + INTERVAL 2 MONTH;
SYSTEM START MERGES default.trace_conversation;
SYSTEM START MERGES default.trace_event;
SYSTEM START MERGES default.long_task_node_execution_log;
SYSTEM START MERGES default.skill_flow_notification_link_click;
SYSTEM START MERGES default.digital_qa_resource_change_event;
SYSTEM START MERGES default.digital_qa_resource_change_detail;
SYSTEM START MERGES default.platform_orders;
-- 此处检查业务读写、时间显示、environment 过滤，再恢复任务/消费。
-- 保留 __bak_20261103 表，确认观察期结束后另行决定清理；本脚本不自动删除。

-- 回滚参考：仅全部切换完成且尚未恢复业务写入时，停写状态下逐条执行。
-- 以下故意注释，正常迁移不要执行；发生部分切换时只回滚实际已切换的表。
-- 若已恢复写入，先合并新增数据，否则直接回滚会使新记录不可见。
-- RENAME TABLE default.trace_conversation TO default.trace_conversation__failed_20261103, default.trace_conversation__bak_20261103 TO default.trace_conversation;
-- SYSTEM START MERGES default.trace_conversation;
-- RENAME TABLE default.trace_event TO default.trace_event__failed_20261103, default.trace_event__bak_20261103 TO default.trace_event;
-- SYSTEM START MERGES default.trace_event;
-- RENAME TABLE default.long_task_node_execution_log TO default.long_task_node_execution_log__failed_20261103, default.long_task_node_execution_log__bak_20261103 TO default.long_task_node_execution_log;
-- SYSTEM START MERGES default.long_task_node_execution_log;
-- RENAME TABLE default.skill_flow_notification_link_click TO default.skill_flow_notification_link_click__failed_20261103, default.skill_flow_notification_link_click__bak_20261103 TO default.skill_flow_notification_link_click;
-- SYSTEM START MERGES default.skill_flow_notification_link_click;
-- RENAME TABLE default.digital_qa_resource_change_event TO default.digital_qa_resource_change_event__failed_20261103, default.digital_qa_resource_change_event__bak_20261103 TO default.digital_qa_resource_change_event;
-- SYSTEM START MERGES default.digital_qa_resource_change_event;
-- RENAME TABLE default.digital_qa_resource_change_detail TO default.digital_qa_resource_change_detail__failed_20261103, default.digital_qa_resource_change_detail__bak_20261103 TO default.digital_qa_resource_change_detail;
-- SYSTEM START MERGES default.digital_qa_resource_change_detail;
-- RENAME TABLE default.platform_orders TO default.platform_orders__failed_20261103, default.platform_orders__bak_20261103 TO default.platform_orders;
-- SYSTEM START MERGES default.platform_orders;
-- 若在切换前中止：源表仍是正式表，对阶段 1 停止合并的每张源表执行 SYSTEM START MERGES。
