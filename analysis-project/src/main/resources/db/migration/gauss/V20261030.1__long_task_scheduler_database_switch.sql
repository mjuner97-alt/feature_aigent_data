INSERT INTO ai_chat_runtime_config (config_key, config_value, config_description)
SELECT 'long_task_scheduler_enabled', 'true',
       '长任务定时扫描数据库总开关；true 允许扫描，false 一键停止所有自动调度。需同时满足 scheduler.enabled=true。'
WHERE NOT EXISTS (SELECT 1 FROM ai_chat_runtime_config WHERE config_key = 'long_task_scheduler_enabled');
