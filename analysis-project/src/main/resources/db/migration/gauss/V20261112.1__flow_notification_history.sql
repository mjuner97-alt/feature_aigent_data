-- 一次性迁移。手工部署时在同一个事务中执行整份脚本。
BEGIN;
LOCK TABLE skill_flow_notification IN SHARE ROW EXCLUSIVE MODE;

-- CTAS 只复制数据与字段类型，不继承外键、默认值和序列。
CREATE TABLE skill_flow_notification_history AS
SELECT n.id, n.flow_execution_id, n.delivery_key, n.status, n.recipient,
       n.channel, n.request_json, n.response_json, n.error_message,
       n.sent_at, n.created_at, n.updated_at,
       e.flow_id, e.flow_name, e.trigger_type
FROM skill_flow_notification n
JOIN skill_flow_execution e ON e.id = n.flow_execution_id;

ALTER TABLE skill_flow_notification_history ADD PRIMARY KEY (id);
CREATE INDEX idx_flow_notification_history_sent_at ON skill_flow_notification_history (sent_at);
CREATE INDEX idx_flow_notification_history_execution ON skill_flow_notification_history (flow_execution_id);
COMMENT ON TABLE skill_flow_notification_history IS '长任务通知历史：每次通知一行，清理任务执行时永久保留';
COMMENT ON COLUMN skill_flow_notification_history.flow_execution_id IS '原执行 ID，仅用于追溯，无外键';

CREATE OR REPLACE FUNCTION sync_flow_notification_history()
RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        IF OLD.status IN ('PENDING', 'SENDING') THEN
            RAISE EXCEPTION 'Notification % is still in flight', OLD.id;
        END IF;
        IF NOT EXISTS (
            SELECT 1 FROM skill_flow_notification_history h
            WHERE h.id = OLD.id AND h.flow_execution_id = OLD.flow_execution_id
              AND h.status = OLD.status
              AND h.updated_at IS NOT DISTINCT FROM OLD.updated_at
        ) THEN
            RAISE EXCEPTION 'Notification % is missing from history or out of sync', OLD.id;
        END IF;
        RETURN OLD;
    END IF;

    -- 已归档的任务名称和触发方式保留首次快照；更新实际投递结果。
    UPDATE skill_flow_notification_history
    SET status = NEW.status, recipient = NEW.recipient, channel = NEW.channel,
        request_json = NEW.request_json, response_json = NEW.response_json,
        error_message = NEW.error_message, sent_at = NEW.sent_at, updated_at = NEW.updated_at
    WHERE id = NEW.id;
    IF NOT FOUND THEN
        INSERT INTO skill_flow_notification_history (
            id, flow_execution_id, delivery_key, status, recipient, channel,
            request_json, response_json, error_message, sent_at, created_at, updated_at,
            flow_id, flow_name, trigger_type
        )
        SELECT NEW.id, NEW.flow_execution_id, NEW.delivery_key, NEW.status, NEW.recipient,
               NEW.channel, NEW.request_json, NEW.response_json, NEW.error_message,
               NEW.sent_at, NEW.created_at, NEW.updated_at, e.flow_id, e.flow_name, e.trigger_type
        FROM skill_flow_execution e
        WHERE e.id = NEW.flow_execution_id;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'Missing execution snapshot for notification %', NEW.id;
        END IF;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_flow_notification_history_sync
AFTER INSERT OR UPDATE ON skill_flow_notification
FOR EACH ROW EXECUTE PROCEDURE sync_flow_notification_history();

CREATE TRIGGER trg_flow_notification_history_guard
BEFORE DELETE ON skill_flow_notification
FOR EACH ROW EXECUTE PROCEDURE sync_flow_notification_history();
COMMIT;
