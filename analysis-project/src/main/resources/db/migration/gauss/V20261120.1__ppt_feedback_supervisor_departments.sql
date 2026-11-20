-- 旧名单保留，只有明确配置督办部门后才参与邮件抄送。
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = current_schema() AND table_name = 'digital_qa_ppt_feedback_supervisors'
            AND column_name = 'department_assignments'
    ) THEN
        ALTER TABLE digital_qa_ppt_feedback_supervisors
            ADD COLUMN department_assignments TEXT NOT NULL DEFAULT '{}';
    END IF;
END $$;

COMMENT ON COLUMN digital_qa_ppt_feedback_supervisors.department_assignments
    IS '按统一认证号配置的督办部门 JSON 对象，未配置的人员不抄送';
