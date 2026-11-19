-- 合并事项分派通知、反馈督办及督办人启停迁移，兼容已执行部分旧脚本的环境。

-- 分派通知与分派变更同事务落库，每次变更、每位新增经办人一条。
CREATE TABLE IF NOT EXISTS digital_qa_ppt_assignment_notice (
    event_id VARCHAR(36) NOT NULL,
    version_id BIGINT NOT NULL REFERENCES digital_qa_ppt_material_version(id) ON DELETE CASCADE,
    finding_id BIGINT NOT NULL REFERENCES digital_qa_ppt_finding(id) ON DELETE CASCADE,
    recipient VARCHAR(255) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    attempts INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    attempted_at TIMESTAMP,
    sent_at TIMESTAMP,
    PRIMARY KEY(event_id, recipient)
);
COMMENT ON TABLE digital_qa_ppt_assignment_notice IS '例会材料事项分派通知任务，按分派变更事件和新增经办人分别记录';
COMMENT ON COLUMN digital_qa_ppt_assignment_notice.event_id IS '分派变更事件标识，同一次变更产生的通知共用此标识';
COMMENT ON COLUMN digital_qa_ppt_assignment_notice.version_id IS '事项关联的材料版本 ID';
COMMENT ON COLUMN digital_qa_ppt_assignment_notice.finding_id IS '通知对应的反馈事项 ID';
COMMENT ON COLUMN digital_qa_ppt_assignment_notice.recipient IS '新增经办人的统一认证号，与事件标识共同组成主键';
COMMENT ON COLUMN digital_qa_ppt_assignment_notice.status IS '任务状态：PENDING 待发送、FAILED 发送失败待重试、SENT 已发送、CANCELLED 已取消';
COMMENT ON COLUMN digital_qa_ppt_assignment_notice.attempts IS '任务处理次数，包含发送、重试和取消处理';
COMMENT ON COLUMN digital_qa_ppt_assignment_notice.created_at IS '通知任务创建时间';
COMMENT ON COLUMN digital_qa_ppt_assignment_notice.attempted_at IS '最近一次任务处理时间，用于控制失败重试间隔';
COMMENT ON COLUMN digital_qa_ppt_assignment_notice.sent_at IS '通知发送成功时间，未发送成功时为空';

CREATE INDEX IF NOT EXISTS idx_ppt_assignment_notice_pending ON digital_qa_ppt_assignment_notice(status, attempted_at);

DO $$
BEGIN
    BEGIN
        ALTER TABLE digital_qa_ppt_finding ADD COLUMN deleted_by VARCHAR(128);
    EXCEPTION WHEN duplicate_column THEN NULL;
    END;
    BEGIN
        ALTER TABLE digital_qa_ppt_finding ADD COLUMN deleted_at TIMESTAMP;
    EXCEPTION WHEN duplicate_column THEN NULL;
    END;
    BEGIN
        ALTER TABLE digital_qa_ppt_finding ADD COLUMN deleted_previous_status VARCHAR(64);
    EXCEPTION WHEN duplicate_column THEN NULL;
    END;
END $$;
COMMENT ON COLUMN digital_qa_ppt_finding.deleted_by IS '执行事项删除的用户统一认证号，未删除时为空';
COMMENT ON COLUMN digital_qa_ppt_finding.deleted_at IS '事项删除时间，未删除时为空';
COMMENT ON COLUMN digital_qa_ppt_finding.deleted_previous_status IS '事项删除前的办理状态，未删除时为空';

-- 反馈督办配置与催办留痕。
CREATE TABLE IF NOT EXISTS digital_qa_ppt_feedback_supervisors (
    config_id INTEGER PRIMARY KEY CHECK (config_id = 1),
    user_ids TEXT NOT NULL,
    updated_by VARCHAR(128) NOT NULL,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE digital_qa_ppt_feedback_supervisors IS '例会材料首次待反馈通知和逾期催办邮件的全局督办人配置';
COMMENT ON COLUMN digital_qa_ppt_feedback_supervisors.config_id IS '配置主键，固定为 1';
COMMENT ON COLUMN digital_qa_ppt_feedback_supervisors.user_ids IS '督办人统一认证号 JSON 数组';
COMMENT ON COLUMN digital_qa_ppt_feedback_supervisors.updated_by IS '最后修改配置的用户统一认证号';
COMMENT ON COLUMN digital_qa_ppt_feedback_supervisors.updated_at IS '最后修改配置的时间';

DO $$
BEGIN
    BEGIN
        ALTER TABLE digital_qa_ppt_feedback_supervisors ADD COLUMN disabled_user_ids TEXT NOT NULL DEFAULT '[]';
    EXCEPTION WHEN duplicate_column THEN NULL;
    END;
END $$;
COMMENT ON COLUMN digital_qa_ppt_feedback_supervisors.disabled_user_ids IS '已禁用的督办人统一认证号 JSON 数组，保留在名单中但不接收督办抄送';

INSERT INTO digital_qa_ppt_feedback_supervisors(config_id,user_ids,updated_by)
SELECT 1,'[]','SYSTEM'
WHERE NOT EXISTS (SELECT 1 FROM digital_qa_ppt_feedback_supervisors WHERE config_id=1);

DO $$
BEGIN
    BEGIN
        ALTER TABLE digital_qa_ppt_finding ADD COLUMN first_feedback_notified_at TIMESTAMP;
    EXCEPTION WHEN duplicate_column THEN NULL;
    END;
    BEGIN
        ALTER TABLE digital_qa_ppt_finding ADD COLUMN last_feedback_reminded_at TIMESTAMP;
    EXCEPTION WHEN duplicate_column THEN NULL;
    END;
END $$;
COMMENT ON COLUMN digital_qa_ppt_finding.first_feedback_notified_at IS '首次成功通知经办人的时间（北京时间），据此计算反馈期限';
COMMENT ON COLUMN digital_qa_ppt_finding.last_feedback_reminded_at IS '最近一次逾期催办成功时间（北京时间）';
CREATE INDEX IF NOT EXISTS idx_ppt_feedback_reminder_due ON digital_qa_ppt_finding (status, first_feedback_notified_at);

DO $$
BEGIN
    BEGIN
        ALTER TABLE digital_qa_ppt_mail_record ADD COLUMN reminder_finding_ids TEXT;
    EXCEPTION WHEN duplicate_column THEN NULL;
    END;
END $$;
COMMENT ON COLUMN digital_qa_ppt_mail_record.reminder_finding_ids IS '本次逾期催办覆盖的事项 ID JSON 数组，其他邮件为空';

-- 已经通知过的待反馈事项沿用原首发时间。
UPDATE digital_qa_ppt_finding f
SET first_feedback_notified_at = history.first_sent_at
FROM (
    SELECT finding_id, MIN(sent_at) AS first_sent_at
    FROM (
        SELECT finding_id, notified_at AS sent_at FROM digital_qa_ppt_feedback_notice WHERE kind = 'PENDING'
        UNION ALL
        SELECT finding_id, sent_at FROM digital_qa_ppt_assignment_notice WHERE status = 'SENT'
    ) notices
    GROUP BY finding_id
) history
WHERE f.id = history.finding_id
  AND f.status = 'PENDING_FEEDBACK'
  AND f.first_feedback_notified_at IS NULL;
