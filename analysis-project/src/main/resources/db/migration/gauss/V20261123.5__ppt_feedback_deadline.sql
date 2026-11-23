ALTER TABLE digital_qa_ppt_finding ADD COLUMN IF NOT EXISTS feedback_deadline_at TIMESTAMP;

COMMENT ON COLUMN digital_qa_ppt_finding.feedback_deadline_at IS '首次待反馈通知约定的反馈截止时间（北京时间），用于催复通知判断是否逾期';
