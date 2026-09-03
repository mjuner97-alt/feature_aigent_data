INSERT INTO ai_chat_runtime_config (config_key, config_value, config_description)
SELECT 'long_task_enabled', 'false', '是否允许 /ai/chat 根据触发词进入长任务流程；true 开启，缺失或其他值按 false 处理。'
WHERE NOT EXISTS (SELECT 1 FROM ai_chat_runtime_config WHERE config_key = 'long_task_enabled');
