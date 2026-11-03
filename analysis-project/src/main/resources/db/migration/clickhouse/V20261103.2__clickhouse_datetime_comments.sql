-- 已有 ClickHouse 表增量调整：DateTime 秒级时间、字段注释，保留原 TTL 周期。
-- 目标服务端 ClickHouse 20.12.3.3，客户端 clickhouse-jdbc 0.2.4。
-- 旧版不支持 ALTER TABLE IF EXISTS；本脚本要求目标表已存在。
-- 列级 COMMENT COLUMN IF EXISTS / MODIFY COLUMN IF EXISTS 在 20.12.3.3 中可用。
-- 使用简化版 Trace 结构；trace_span 已被删除，不再调整。
-- DateTime 为秒级精度，duration_ms 继续保留独立的毫秒耗时。
-- 资源变更时间字段保留 Asia/Shanghai 时区。
-- 仅针对已建表执行；20.12 支持 DateTime64；历史建表中的 Bool 需改用 UInt8（本文件不修改历史建表）。
-- 若现有时间列属于分区键或排序键，DateTime64 -> DateTime 可能被服务端拒绝；
-- 此时需要按实际 SHOW CREATE TABLE 设计新表迁移，不能靠 IF EXISTS 跳过类型转换错误。
-- TTL 单独执行，省略可选 DELETE（20.12 支持显式 DELETE，省略后同样过期删除）。
-- JDBC 执行器须按分号逐条执行，不要将整个文件传给一次 Statement.execute。
-- 未部署的表需在执行器中检查并跳过；列级 IF EXISTS 不能跳过不存在的表。

ALTER TABLE trace_conversation
    COMMENT COLUMN IF EXISTS conversation_id '会话唯一标识',
    COMMENT COLUMN IF EXISTS trace_id '链路追踪标识',
    COMMENT COLUMN IF EXISTS user_id '用户标识',
    COMMENT COLUMN IF EXISTS source '会话来源或智能体名称',
    COMMENT COLUMN IF EXISTS agent_id '智能体标识',
    COMMENT COLUMN IF EXISTS agent_name '智能体名称',
    MODIFY COLUMN IF EXISTS start_ts DateTime COMMENT '开始时间，秒级精度',
    MODIFY COLUMN IF EXISTS end_ts DateTime COMMENT '结束时间，秒级精度',
    COMMENT COLUMN IF EXISTS duration_ms '执行耗时，单位毫秒，独立记录以保留毫秒精度',
    COMMENT COLUMN IF EXISTS status '执行状态',
    COMMENT COLUMN IF EXISTS error_message '错误信息',
    COMMENT COLUMN IF EXISTS event_count '事件数量',
    COMMENT COLUMN IF EXISTS token_input '输入 Token 数量',
    COMMENT COLUMN IF EXISTS token_output '输出 Token 数量',
    COMMENT COLUMN IF EXISTS model '模型名称',
    COMMENT COLUMN IF EXISTS event_date '数据日期，用于分区及 90 天过期清理';

ALTER TABLE trace_conversation
    MODIFY TTL event_date + INTERVAL 90 DAY;

ALTER TABLE trace_event
    COMMENT COLUMN IF EXISTS event_id '事件唯一标识',
    COMMENT COLUMN IF EXISTS conversation_id '会话唯一标识',
    COMMENT COLUMN IF EXISTS trace_id '链路追踪标识',
    COMMENT COLUMN IF EXISTS event_type '事件类型',
    COMMENT COLUMN IF EXISTS event_name '事件名称',
    COMMENT COLUMN IF EXISTS source '事件来源或智能体名称',
    MODIFY COLUMN IF EXISTS timestamp DateTime COMMENT '事件发生时间，秒级精度',
    COMMENT COLUMN IF EXISTS duration_ms '执行耗时，单位毫秒，独立记录以保留毫秒精度',
    COMMENT COLUMN IF EXISTS event_json '事件原始数据，JSON 字符串',
    COMMENT COLUMN IF EXISTS event_date '数据日期，用于分区及 90 天过期清理';

ALTER TABLE trace_event
    MODIFY TTL event_date + INTERVAL 90 DAY;

ALTER TABLE default.long_task_node_execution_log
    COMMENT COLUMN IF EXISTS log_id '日志唯一标识',
    COMMENT COLUMN IF EXISTS flow_execution_id '流程执行标识',
    COMMENT COLUMN IF EXISTS node_execution_id '节点执行标识',
    COMMENT COLUMN IF EXISTS attempt_id '执行尝试标识',
    COMMENT COLUMN IF EXISTS attempt_no '执行尝试次数',
    COMMENT COLUMN IF EXISTS user_id '用户标识',
    COMMENT COLUMN IF EXISTS flow_name '流程名称',
    COMMENT COLUMN IF EXISTS node_key '节点编码',
    COMMENT COLUMN IF EXISTS node_name '节点名称',
    COMMENT COLUMN IF EXISTS script_id '脚本标识',
    COMMENT COLUMN IF EXISTS status '执行状态',
    COMMENT COLUMN IF EXISTS retryable '是否允许重试',
    COMMENT COLUMN IF EXISTS error_code '错误编码',
    MODIFY COLUMN IF EXISTS started_at DateTime COMMENT '开始时间，秒级精度',
    MODIFY COLUMN IF EXISTS completed_at DateTime COMMENT '完成时间，秒级精度',
    COMMENT COLUMN IF EXISTS duration_ms '执行耗时，单位毫秒，独立记录以保留毫秒精度',
    COMMENT COLUMN IF EXISTS params_json '执行参数，JSON 字符串',
    COMMENT COLUMN IF EXISTS output_text '执行输出内容',
    COMMENT COLUMN IF EXISTS error_message '错误信息',
    COMMENT COLUMN IF EXISTS params_truncated '参数是否被截断',
    COMMENT COLUMN IF EXISTS output_truncated '输出是否被截断',
    COMMENT COLUMN IF EXISTS error_truncated '错误内容是否被截断',
    COMMENT COLUMN IF EXISTS params_bytes '原始参数字节数',
    COMMENT COLUMN IF EXISTS output_bytes '原始输出字节数',
    COMMENT COLUMN IF EXISTS error_bytes '原始错误字节数',
    COMMENT COLUMN IF EXISTS event_date '数据日期，用于分区及 90 天过期清理',
    COMMENT COLUMN IF EXISTS execution_ip '执行机器 IP';

ALTER TABLE default.long_task_node_execution_log
    MODIFY TTL event_date + INTERVAL 90 DAY;

ALTER TABLE skill_flow_notification_link_click
    COMMENT COLUMN IF EXISTS event_id '事件唯一标识',
    COMMENT COLUMN IF EXISTS access_token '报告访问令牌',
    COMMENT COLUMN IF EXISTS environment '来源环境：TEST 为测试，PROD 为生产；历史未知环境为空',
    COMMENT COLUMN IF EXISTS notification_id '通知标识',
    COMMENT COLUMN IF EXISTS flow_execution_id '流程执行标识',
    COMMENT COLUMN IF EXISTS job_execution_id '任务执行标识',
    COMMENT COLUMN IF EXISTS actor_user_id '访问用户标识',
    COMMENT COLUMN IF EXISTS source '访问来源',
    COMMENT COLUMN IF EXISTS event_type '事件类型',
    COMMENT COLUMN IF EXISTS logged_in '访问时是否已登录',
    COMMENT COLUMN IF EXISTS event_at '访问发生时间，秒级精度',
    COMMENT COLUMN IF EXISTS client_ip '客户端 IP',
    COMMENT COLUMN IF EXISTS user_agent '客户端 User-Agent',
    COMMENT COLUMN IF EXISTS task_display_name '任务展示名称',
    COMMENT COLUMN IF EXISTS execution_at '任务执行时间，秒级精度',
    COMMENT COLUMN IF EXISTS created_at '记录创建时间，秒级精度';

ALTER TABLE skill_flow_notification_link_click
    MODIFY TTL event_at + INTERVAL 2 MONTH;

ALTER TABLE digital_qa_resource_change_event
    COMMENT COLUMN IF EXISTS event_id '事件唯一标识',
    COMMENT COLUMN IF EXISTS event_no '资源变更事件编号',
    COMMENT COLUMN IF EXISTS resource_type '资源类型',
    COMMENT COLUMN IF EXISTS resource_id '资源标识',
    COMMENT COLUMN IF EXISTS resource_name '资源名称',
    COMMENT COLUMN IF EXISTS operation_type '操作类型',
    COMMENT COLUMN IF EXISTS operation_summary '操作摘要',
    COMMENT COLUMN IF EXISTS operator_id '操作人标识',
    COMMENT COLUMN IF EXISTS operator_name '操作人名称',
    COMMENT COLUMN IF EXISTS operator_type '操作主体类型，默认 user',
    COMMENT COLUMN IF EXISTS source '操作来源，默认 ui',
    COMMENT COLUMN IF EXISTS result '操作结果，默认 success',
    COMMENT COLUMN IF EXISTS error_code '错误编码',
    COMMENT COLUMN IF EXISTS error_message '错误信息',
    COMMENT COLUMN IF EXISTS request_id '请求链路标识',
    COMMENT COLUMN IF EXISTS client_ip '客户端 IP',
    COMMENT COLUMN IF EXISTS user_agent '客户端 User-Agent',
    COMMENT COLUMN IF EXISTS before_version '变更前版本',
    COMMENT COLUMN IF EXISTS after_version '变更后版本',
    COMMENT COLUMN IF EXISTS before_hash '变更前内容哈希',
    COMMENT COLUMN IF EXISTS after_hash '变更后内容哈希',
    COMMENT COLUMN IF EXISTS detail_count '变更明细数量',
    MODIFY COLUMN IF EXISTS created_at DateTime('Asia/Shanghai') COMMENT '记录创建时间，秒级精度',
    COMMENT COLUMN IF EXISTS extra_data '扩展数据',
    COMMENT COLUMN IF EXISTS environment '来源环境：TEST 为测试，PROD 为生产；历史未知环境为空';

ALTER TABLE digital_qa_resource_change_event
    MODIFY TTL created_at + INTERVAL 2 MONTH;

ALTER TABLE digital_qa_resource_change_detail
    COMMENT COLUMN IF EXISTS event_id '事件唯一标识',
    COMMENT COLUMN IF EXISTS detail_no '变更明细序号',
    COMMENT COLUMN IF EXISTS resource_type '资源类型',
    COMMENT COLUMN IF EXISTS resource_id '资源标识',
    COMMENT COLUMN IF EXISTS target_type '变更目标类型',
    COMMENT COLUMN IF EXISTS target_id '变更目标标识',
    COMMENT COLUMN IF EXISTS target_name '变更目标名称',
    COMMENT COLUMN IF EXISTS field_name '字段名称',
    COMMENT COLUMN IF EXISTS field_label '字段展示名称',
    COMMENT COLUMN IF EXISTS change_type '变更类型',
    COMMENT COLUMN IF EXISTS before_value '变更前原始值',
    COMMENT COLUMN IF EXISTS after_value '变更后原始值',
    COMMENT COLUMN IF EXISTS before_value_json '变更前值，JSON 字符串',
    COMMENT COLUMN IF EXISTS after_value_json '变更后值，JSON 字符串',
    COMMENT COLUMN IF EXISTS display_before_value '变更前展示值',
    COMMENT COLUMN IF EXISTS display_after_value '变更后展示值',
    COMMENT COLUMN IF EXISTS value_type '字段值类型',
    COMMENT COLUMN IF EXISTS is_sensitive '是否为敏感字段',
    COMMENT COLUMN IF EXISTS is_truncated '字段值是否被截断',
    COMMENT COLUMN IF EXISTS sort_order '明细展示顺序',
    MODIFY COLUMN IF EXISTS created_at DateTime('Asia/Shanghai') COMMENT '记录创建时间，秒级精度';

ALTER TABLE digital_qa_resource_change_detail
    MODIFY TTL created_at + INTERVAL 2 MONTH;

ALTER TABLE platform_orders
    COMMENT COLUMN IF EXISTS order_id '订单标识',
    COMMENT COLUMN IF EXISTS user_id '用户标识',
    COMMENT COLUMN IF EXISTS product '产品名称',
    COMMENT COLUMN IF EXISTS amount '订单金额',
    COMMENT COLUMN IF EXISTS order_date '订单日期';
