INSERT INTO ai_chat_runtime_config (config_key, config_value, config_description)
SELECT 'long_task_scheduler_ip', '', '允许执行自动触发长任务（AUTO_METRIC）的服务器本机网卡 IP；单个非回环 IP。空值、不匹配或读取失败时禁止自动任务，手动和聊天执行不受限制。'
WHERE NOT EXISTS (
    SELECT 1 FROM ai_chat_runtime_config WHERE config_key = 'long_task_scheduler_ip'
);
