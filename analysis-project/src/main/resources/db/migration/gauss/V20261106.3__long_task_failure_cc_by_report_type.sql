-- 自动触发首次失败通知：部门报告、团队报告分别维护抄送人，默认均为空。
-- 不复制旧全局名单，防止其他报告或不同类型报告被误抄送。
INSERT INTO ai_chat_runtime_config (config_key, config_value, config_description)
SELECT 'long_task_department_failure_cc_recipients', '', '部门报告自动触发首次失败通知抄送用户ID，多个使用中英文逗号、分号或换行分隔；为空不抄送'
WHERE NOT EXISTS (
    SELECT 1 FROM ai_chat_runtime_config WHERE config_key = 'long_task_department_failure_cc_recipients'
);

INSERT INTO ai_chat_runtime_config (config_key, config_value, config_description)
SELECT 'long_task_team_failure_cc_recipients', '', '团队报告自动触发首次失败通知抄送用户ID，多个使用中英文逗号、分号或换行分隔；为空不抄送'
WHERE NOT EXISTS (
    SELECT 1 FROM ai_chat_runtime_config WHERE config_key = 'long_task_team_failure_cc_recipients'
);
