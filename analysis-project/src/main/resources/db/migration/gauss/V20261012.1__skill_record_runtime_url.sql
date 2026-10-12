INSERT INTO ai_chat_runtime_config (config_key, config_value, config_description)
SELECT 'skill_record_url', 'http://122.244.14.11:2021/skillRecord/record', '设为案例接口地址'
WHERE NOT EXISTS (
    SELECT 1 FROM ai_chat_runtime_config WHERE config_key = 'skill_record_url'
);
