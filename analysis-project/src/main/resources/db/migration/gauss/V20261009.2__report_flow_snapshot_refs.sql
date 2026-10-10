ALTER TABLE skill_flow ADD COLUMN IF NOT EXISTS report_process_id BIGINT;
ALTER TABLE skill_job ADD COLUMN IF NOT EXISTS report_process_id BIGINT;
CREATE INDEX IF NOT EXISTS idx_skill_flow_report_process ON skill_flow(report_process_id);
CREATE INDEX IF NOT EXISTS idx_skill_job_report_process ON skill_job(report_process_id);

COMMENT ON COLUMN skill_flow.report_process_id IS '引入的报告流程来源 ID；执行时读取报告流程最新定义';
COMMENT ON COLUMN skill_job.report_process_id IS '引入的报告流程来源 ID；执行时读取报告流程最新定义';
