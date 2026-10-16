-- 邮件收件人管理：支持 TO/CC 组内顺序、触发范围显式清空和执行快照。
-- 使用独立排序值，避免人员展示及发送顺序依赖关系表主键。
ALTER TABLE notification_recipient ADD COLUMN IF NOT EXISTS sort_order INTEGER NOT NULL DEFAULT 0;
COMMENT ON COLUMN notification_recipient.sort_order IS '同一触发范围与收件人类型内的展示及发送顺序，从 0 开始；历史记录按主键补充排序';
COMMENT ON COLUMN notification_recipient.recipient_type IS '邮件收件人类型：TO 收件人、CC 抄送人；同一触发范围内跨组去重';

-- 历史记录排序值均为 0，继续按主键补充排序，以保持原名单顺序。
-- 范围标记复用关系表：业务层在显式清空名单时写入空 user_id 哨兵行，无需独立标记表。
-- 读名单时过滤空 user_id，判定范围是否配置时保留哨兵行，避免主动清空后再次继承 DEFAULT。
COMMENT ON COLUMN notification_recipient.user_id IS '收件人用户 ID，需通过人员服务校验；空串为显式清空范围的内部标记，不参与发送及人员展示';

-- 执行创建时固化抄送名单，后续修改流程配置不影响已启动执行；历史执行默认为无抄送。
ALTER TABLE skill_flow_execution ADD COLUMN IF NOT EXISTS cc_receivers_snapshot TEXT;
COMMENT ON COLUMN skill_flow_execution.cc_receivers_snapshot IS '执行创建时的有序 CC 用户 ID 快照，逗号分隔；空表示无抄送，不触发默认收件人回退';
-- 批量名单可能超过旧字段长度，扩大长任务定义、快照和通知记录字段容量。
ALTER TABLE skill_flow ALTER COLUMN notify_receivers TYPE TEXT;
ALTER TABLE skill_flow_execution ALTER COLUMN notify_receivers_snapshot TYPE TEXT;
ALTER TABLE skill_flow_notification ALTER COLUMN recipient TYPE TEXT;
COMMENT ON COLUMN skill_flow.notify_receivers IS '长任务有序 TO 用户 ID 兼容字段，逗号分隔；已有通知配置时优先读取收件人关系表';
COMMENT ON COLUMN skill_flow_execution.notify_receivers_snapshot IS '执行创建时的有序 TO 用户 ID 快照，逗号分隔；有效名单为空时回退流程触发人';
COMMENT ON COLUMN skill_flow_notification.recipient IS '本次长任务通知实际使用的 TO 用户 ID，逗号分隔，保留发送顺序';
