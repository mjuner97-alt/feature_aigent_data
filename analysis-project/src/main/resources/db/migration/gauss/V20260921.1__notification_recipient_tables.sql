-- ============================================================================
-- 通知收件人关系表:通用通知配置(notification_config)与收件人关系(notification_recipient)
--
-- 背景:skill_job.notify_receivers / skill_flow.notify_receivers 以逗号分隔
--       用户 ID 保存收件人名单,不利于复用通知能力、查询订阅关系与扩展渠道。
--       本迁移引入通用通知配置与收件人关系表作为关系化替代;旧字段第一阶段
--       保留作为迁移兼容字段,不做任何修改。
--
-- 本迁移:
--   1. notification_config:通用通知配置主表,一个业务对象(SKILL_JOB/SKILL_FLOW)
--      对应一条配置记录;target_id 是多态业务 ID,不加业务外键,由 Service 层校验。
--   2. notification_recipient:配置与人员的关系表,第一期固定 TO + EMAIL,
--      字段保留 CC/BCC 与其他渠道扩展能力;唯一约束防止同一配置重复添加
--      同一用户同一渠道的关系。
-- ============================================================================

CREATE TABLE IF NOT EXISTS notification_config (
    id BIGSERIAL PRIMARY KEY,
    target_type VARCHAR(32) NOT NULL,
    target_id BIGINT NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_by VARCHAR(64),
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

COMMENT ON TABLE notification_config IS '通用通知配置表,关联独立任务、长任务及后续可扩展业务对象';
COMMENT ON COLUMN notification_config.id IS '通知配置主键';
COMMENT ON COLUMN notification_config.target_type IS '通知目标类型,例如 SKILL_JOB、SKILL_FLOW';
COMMENT ON COLUMN notification_config.target_id IS '通知目标业务主键,与 target_type 共同确定业务对象,由 Service 层按 target_type 校验,无数据库外键';
COMMENT ON COLUMN notification_config.enabled IS '该通知配置记录是否启用(非流程完成通知开关,通知开关仍由任务/流程自身配置负责)';
COMMENT ON COLUMN notification_config.created_by IS '配置创建人用户 ID';
COMMENT ON COLUMN notification_config.created_at IS '配置创建时间';
COMMENT ON COLUMN notification_config.updated_at IS '配置最后更新时间';

-- 一个业务对象只允许一条配置记录
CREATE UNIQUE INDEX IF NOT EXISTS uk_notification_config_target
    ON notification_config (target_type, target_id);

CREATE INDEX IF NOT EXISTS idx_notification_config_enabled
    ON notification_config (target_type, enabled);

CREATE TABLE IF NOT EXISTS notification_recipient (
    id BIGSERIAL PRIMARY KEY,
    config_id BIGINT NOT NULL REFERENCES notification_config(id),
    user_id VARCHAR(64) NOT NULL,
    recipient_type VARCHAR(16) NOT NULL DEFAULT 'TO',
    channel VARCHAR(16) NOT NULL DEFAULT 'EMAIL',
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

COMMENT ON TABLE notification_recipient IS '通知配置收件人关系表,保存业务通知与用户之间的多对多关系';
COMMENT ON COLUMN notification_recipient.id IS '收件人关系主键';
COMMENT ON COLUMN notification_recipient.config_id IS '关联 notification_config.id';
COMMENT ON COLUMN notification_recipient.user_id IS '收件人用户 ID,需通过人员服务校验';
COMMENT ON COLUMN notification_recipient.recipient_type IS '收件人类型,第一期固定为 TO,预留 CC/BCC';
COMMENT ON COLUMN notification_recipient.channel IS '通知渠道,第一期固定为 EMAIL';
COMMENT ON COLUMN notification_recipient.enabled IS '该收件人关系是否启用';
COMMENT ON COLUMN notification_recipient.created_at IS '关系创建时间';
COMMENT ON COLUMN notification_recipient.updated_at IS '关系最后更新时间';

-- 同一配置下,同一用户同一渠道同一收件人类型只允许一条关系
CREATE UNIQUE INDEX IF NOT EXISTS uk_notification_recipient
    ON notification_recipient (config_id, user_id, recipient_type, channel);

CREATE INDEX IF NOT EXISTS idx_notification_recipient_user
    ON notification_recipient (user_id, enabled);

CREATE INDEX IF NOT EXISTS idx_notification_recipient_config
    ON notification_recipient (config_id, enabled);
