-- ============================================================================
-- 依赖指标每日就绪预警
--
-- 目的：每天 11:00 检查配置为“启用且参与预警”的依赖指标，并向 runtime
--       配置的收件人发送就绪状态汇总。该功能只依赖指标就绪事实，不关联
--       长任务或流程配置。
--
-- 幂等性：本迁移可重复执行；字段、runtime 配置和投递表均使用存在性判断。
-- 注意：alert_enabled 默认关闭，避免迁移完成后立即扩大通知范围；业务人员
--       需要按环境显式开启指标预警，并配置收件人。
-- ============================================================================

-- ----------------------------------------------------------------------------
-- 1. 指标预警开关
-- ----------------------------------------------------------------------------
-- 只有 enabled=true 且 alert_enabled=true 的指标进入每日 11:00 检查清单。
ALTER TABLE skill_dependency_metric
    ADD COLUMN IF NOT EXISTS alert_enabled BOOLEAN DEFAULT FALSE NOT NULL;

COMMENT ON COLUMN skill_dependency_metric.alert_enabled IS '是否参与每日 11:00 依赖指标就绪预警';

-- ----------------------------------------------------------------------------
-- 2. 全局预警收件人配置
-- ----------------------------------------------------------------------------
-- config_value 存放用户工号，多个工号使用英文逗号分隔，例如：user001,user002。
-- 发送时由应用层清洗空白、去重并过滤人员台账中不存在的工号。
INSERT INTO ai_chat_runtime_config (config_key, config_value, config_description)
SELECT 'dependency_metric_alert_recipients', '', '依赖指标每日 11 点预警收件人工号，多个使用英文逗号分隔'
WHERE NOT EXISTS (
    SELECT 1 FROM ai_chat_runtime_config WHERE config_key = 'dependency_metric_alert_recipients'
);

-- ----------------------------------------------------------------------------
-- 3. 每日预警投递记录
-- ----------------------------------------------------------------------------
-- 一天只允许一条首发记录，alert_date 唯一约束用于多实例和重试场景下的幂等。
-- content 保存发送时的 HTML 快照，便于排查当日实际通知内容。
CREATE TABLE IF NOT EXISTS dependency_metric_alert_delivery (
    id BIGSERIAL PRIMARY KEY,
    alert_date DATE NOT NULL,
    status VARCHAR(16) NOT NULL,
    recipient_summary TEXT,
    content TEXT,
    requested_at TIMESTAMP NOT NULL DEFAULT NOW(),
    completed_at TIMESTAMP,
    error_message TEXT,
    CONSTRAINT uk_dependency_metric_alert_delivery_date UNIQUE (alert_date)
);

COMMENT ON TABLE dependency_metric_alert_delivery IS '依赖指标每日 11 点预警投递记录';
COMMENT ON COLUMN dependency_metric_alert_delivery.id IS '投递记录主键';
COMMENT ON COLUMN dependency_metric_alert_delivery.alert_date IS '预警数据日期；同一天只允许一条首发记录';
COMMENT ON COLUMN dependency_metric_alert_delivery.status IS '投递状态：PENDING/SENDING/SUCCESS/FAILED/SKIPPED';
COMMENT ON COLUMN dependency_metric_alert_delivery.recipient_summary IS '本次通知收件人工号，使用英文逗号分隔';
COMMENT ON COLUMN dependency_metric_alert_delivery.content IS '发送时生成的 HTML 通知正文快照';
COMMENT ON COLUMN dependency_metric_alert_delivery.requested_at IS '创建投递记录的时间';
COMMENT ON COLUMN dependency_metric_alert_delivery.completed_at IS '投递进入终态的时间';
COMMENT ON COLUMN dependency_metric_alert_delivery.error_message IS '投递失败或跳过原因';
