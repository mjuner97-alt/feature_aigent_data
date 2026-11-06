-- 自动触发长任务的首次失败通知默认抄送人，配置为空时不额外抄送。
INSERT INTO ai_chat_runtime_config (config_key, config_value, config_description)
SELECT 'long_task_failure_cc_recipients', '', '自动触发长任务首次失败通知默认抄送用户ID，多个使用中英文逗号、分号或换行分隔；仅发送给有效人员，收件人优先去重'
WHERE NOT EXISTS (
    SELECT 1 FROM ai_chat_runtime_config WHERE config_key = 'long_task_failure_cc_recipients'
);
