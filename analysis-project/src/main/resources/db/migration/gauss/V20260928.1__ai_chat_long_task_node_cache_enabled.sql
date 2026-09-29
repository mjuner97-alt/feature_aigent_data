INSERT INTO ai_chat_runtime_config (config_key, config_value, config_description)
SELECT 'long_task_node_cache_enabled', 'true', '是否启用长任务节点结果缓存；true 启用，false 跳过缓存读写但不影响节点执行。'
WHERE NOT EXISTS (
    SELECT 1 FROM ai_chat_runtime_config WHERE config_key = 'long_task_node_cache_enabled'
);
