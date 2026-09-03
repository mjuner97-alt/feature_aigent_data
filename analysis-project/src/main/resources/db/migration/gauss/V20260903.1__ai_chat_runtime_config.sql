-- 创建表，添加表和字段注释
CREATE TABLE IF NOT EXISTS ai_chat_runtime_config (
                                                      config_key VARCHAR(64) PRIMARY KEY,
    config_value VARCHAR(256) NOT NULL,
    config_description VARCHAR(512) NOT NULL,
    updated_at TIMESTAMP NOT NULL DEFAULT now()
    ) WITH (orientation = row, compression = no);

-- 表注释
COMMENT ON TABLE ai_chat_runtime_config IS 'AI聊天运行时配置表，存储模型调用超时、重试次数、流式超时和长任务开关等运行时参数';

-- 字段注释
COMMENT ON COLUMN ai_chat_runtime_config.config_key IS '配置键名，主键';
COMMENT ON COLUMN ai_chat_runtime_config.config_value IS '配置值，存储具体配置参数';
COMMENT ON COLUMN ai_chat_runtime_config.config_description IS '配置描述，说明该配置项的用途和有效范围';
COMMENT ON COLUMN ai_chat_runtime_config.updated_at IS '更新时间，记录配置最后修改时间';

-- 插入初始配置数据
INSERT INTO ai_chat_runtime_config (config_key, config_value, config_description)
VALUES
    ('model_timeout_seconds', '120', '模型请求首包等待时间，单位秒；超时后本次模型调用失败。有效范围：10-600。'),
    ('model_retry_count', '3', '主模型请求失败后的重试次数；0 表示不重试，耗尽后切换备用模型。有效范围：0-10。'),
    ('stream_timeout_seconds', '1200', '整个 /ai/chat SSE 流允许的最长处理时间，单位秒；超时后停止模型和工具执行。有效范围：60-3600。'),
    ('long_task_enabled', 'false', '是否允许 /ai/chat 根据触发词进入长任务流程；true 开启，缺失或其他值按 false 处理。'),
    ('script_exec_enabled', 'false', '是否允许 /ai/chat 执行 script_exec 工具并输出可渲染结果；true 开启，缺失或其他值按 false 处理。');
