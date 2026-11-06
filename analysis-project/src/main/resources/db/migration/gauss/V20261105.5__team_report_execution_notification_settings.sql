-- 空值保留各历史任务的有效设置，直到下次保存。
ALTER TABLE skill_flow_report_param ADD COLUMN executors_json TEXT;
ALTER TABLE skill_flow_report_param ADD COLUMN notify_enabled BOOLEAN;
ALTER TABLE skill_flow_report_param ADD COLUMN notify_body_template TEXT;
COMMENT ON COLUMN skill_flow_report_param.executors_json IS '本团队可执行人；NULL 兼容任务旧授权，空数组表示仅创建人';
COMMENT ON COLUMN skill_flow_report_param.notify_enabled IS '本团队报告完成通知开关；NULL 兼容任务旧配置';
COMMENT ON COLUMN skill_flow_report_param.notify_body_template IS '本团队通知邮件正文；NULL 兼容任务旧配置，空字符串使用默认正文';
