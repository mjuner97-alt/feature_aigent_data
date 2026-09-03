ALTER TABLE skill_job_execution ADD COLUMN IF NOT EXISTS report_markdown TEXT;
COMMENT ON COLUMN skill_job_execution.report_markdown IS '独立任务最终 Markdown 源，文件丢失时用于重新渲染 HTML';
