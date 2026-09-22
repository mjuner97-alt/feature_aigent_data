-- Allow one task/flow to keep different recipient lists per trigger source.
ALTER TABLE notification_recipient
    ADD COLUMN IF NOT EXISTS trigger_type VARCHAR(32) NOT NULL DEFAULT 'DEFAULT';

COMMENT ON COLUMN notification_recipient.trigger_type IS
    '触发来源范围: DEFAULT、AUTO_METRIC、MANUAL、CHAT;同一业务对象可按触发来源配置不同收件人';

DROP INDEX IF EXISTS uk_notification_recipient;
CREATE UNIQUE INDEX IF NOT EXISTS uk_notification_recipient
    ON notification_recipient (config_id, trigger_type, user_id, recipient_type, channel);

CREATE INDEX IF NOT EXISTS idx_notification_recipient_trigger
    ON notification_recipient (config_id, trigger_type, enabled);
