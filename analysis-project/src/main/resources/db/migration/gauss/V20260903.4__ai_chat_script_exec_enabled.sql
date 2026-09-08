INSERT INTO ai_chat_runtime_config (config_key, config_value, config_description)
SELECT 'script_exec_enabled', 'false', '是否允许 /ai/chat 执行 script_exec 工具并输出可渲染结果；true 开启，缺失或其他值按 false 处理。'
WHERE NOT EXISTS (SELECT 1 FROM ai_chat_runtime_config WHERE config_key = 'script_exec_enabled');
