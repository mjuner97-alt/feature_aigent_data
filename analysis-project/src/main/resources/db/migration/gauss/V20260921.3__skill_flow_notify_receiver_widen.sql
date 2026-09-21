-- notify_receiver / notify_receiver_triggers 存逗号分隔的统一认证号名单,
-- 手工建列时宽度 128 不够(多收件人即超长),统一加宽到 1024。
ALTER TABLE skill_flow ALTER COLUMN notify_receivers TYPE VARCHAR(1024);
ALTER TABLE skill_flow ALTER COLUMN notify_receiver_triggers TYPE VARCHAR(1024);

ALTER TABLE skill_flow_execution ALTER COLUMN notify_receivers_snapshot TYPE VARCHAR(1024);
ALTER TABLE skill_flow_execution ALTER COLUMN notify_receiver_triggers_snapshot TYPE VARCHAR(1024);
