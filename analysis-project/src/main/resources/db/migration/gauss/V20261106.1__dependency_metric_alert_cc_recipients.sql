-- 每日 11 点指标预警：支持独立抄送名单，记录每次投递的抄送快照。
INSERT INTO ai_chat_runtime_config (config_key, config_value, config_description)
SELECT 'dependency_metric_alert_cc_recipients', '', '依赖指标每日 11 点预警抄送用户ID，多个使用中英文逗号、分号或换行分隔'
WHERE NOT EXISTS (
    SELECT 1 FROM ai_chat_runtime_config WHERE config_key = 'dependency_metric_alert_cc_recipients'
);

UPDATE ai_chat_runtime_config
SET config_description = '依赖指标每日 11 点预警收件用户ID，多个使用中英文逗号、分号或换行分隔；仅发送给人员表中存在的用户'
WHERE config_key = 'dependency_metric_alert_recipients';

ALTER TABLE dependency_metric_alert_delivery
    ADD COLUMN IF NOT EXISTS cc_recipient_summary TEXT;
COMMENT ON COLUMN dependency_metric_alert_delivery.cc_recipient_summary IS '本次预警抄送用户ID快照，英文逗号分隔；收件人优先，跨组去重';
