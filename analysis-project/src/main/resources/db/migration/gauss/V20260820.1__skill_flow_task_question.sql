ALTER TABLE skill_flow
    ADD COLUMN task_question TEXT;

UPDATE skill_flow
SET task_question = COALESCE(NULLIF(BTRIM(description), ''), name)
WHERE task_question IS NULL OR BTRIM(task_question) = '';

ALTER TABLE skill_flow
    ALTER COLUMN task_question SET NOT NULL;

COMMENT ON COLUMN skill_flow.task_question IS '指标全部就绪后自动执行流程时使用的业务问题';
