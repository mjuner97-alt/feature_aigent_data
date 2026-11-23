ALTER TABLE digital_qa_ppt_assignment_notice
    ADD COLUMN IF NOT EXISTS previous_department VARCHAR(255),
    ADD COLUMN IF NOT EXISTS previous_handlers TEXT;

COMMENT ON TABLE digital_qa_ppt_assignment_notice IS '例会材料事项分派通知任务，按分派变更事件和新增经办人分别记录';
COMMENT ON COLUMN digital_qa_ppt_assignment_notice.previous_department IS '调整前的事项责任部门，原归属为空或历史任务未记录时为空';
COMMENT ON COLUMN digital_qa_ppt_assignment_notice.previous_handlers IS '调整前的经办人统一认证号 JSON 数组，历史任务未记录时为空';

CREATE TABLE IF NOT EXISTS digital_qa_ppt_feedback_reminder_settings (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    enabled BOOLEAN NOT NULL,
    cutoff VARCHAR(5) NOT NULL,
    interval_minutes INTEGER NOT NULL DEFAULT 60 CHECK (interval_minutes > 0)
);

COMMENT ON TABLE digital_qa_ppt_feedback_reminder_settings IS '例会材料反馈逾期提醒的全局配置';
COMMENT ON COLUMN digital_qa_ppt_feedback_reminder_settings.id IS '单条全局配置记录的固定主键，值为 1';
COMMENT ON COLUMN digital_qa_ppt_feedback_reminder_settings.enabled IS '是否开启自动逾期提醒';
COMMENT ON COLUMN digital_qa_ppt_feedback_reminder_settings.cutoff IS '每日提醒截止时间，按北京时间 HH:mm 保存，到达该时间即停止发送';
COMMENT ON COLUMN digital_qa_ppt_feedback_reminder_settings.interval_minutes IS '首次通知后及两次逾期提醒之间的最短间隔，单位为分钟';

INSERT INTO digital_qa_ppt_feedback_reminder_settings(id, enabled, cutoff, interval_minutes)
SELECT 1, TRUE, '18:00', 60
WHERE NOT EXISTS (
    SELECT 1 FROM digital_qa_ppt_feedback_reminder_settings WHERE id = 1
);
