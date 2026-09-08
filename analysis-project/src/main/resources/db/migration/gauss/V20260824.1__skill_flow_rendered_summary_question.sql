-- openGauss 不支持 ADD COLUMN IF NOT EXISTS,重复执行前先确认列不存在
ALTER TABLE skill_flow_execution
    ADD COLUMN rendered_summary_question TEXT NULL;

COMMENT ON COLUMN skill_flow_execution.rendered_summary_question
    IS '最终汇总实际发送给模型的问题，已完成模板变量替换';
