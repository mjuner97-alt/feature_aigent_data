-- 跨任务允许复用团队/部门关键词；同一任务仍不允许规范化关键词重复。
-- 历史建表脚本存在显式命名与数据库自动命名两种唯一约束。
BEGIN;
ALTER TABLE skill_flow_trigger DROP CONSTRAINT IF EXISTS uk_skill_flow_trigger_keyword;
ALTER TABLE skill_flow_trigger DROP CONSTRAINT IF EXISTS skill_flow_trigger_normalized_keyword_key;
ALTER TABLE skill_flow_trigger
    ADD CONSTRAINT uk_skill_flow_trigger_flow_keyword UNIQUE (flow_id, normalized_keyword);
COMMENT ON COLUMN skill_flow_trigger.normalized_keyword IS '规范化关键词；同一流程内唯一，不同流程可重复';
COMMENT ON TABLE skill_flow_trigger IS '长任务聊天触发词；多流程命中时按权限过滤后由用户选择，priority 用于候选排序';
COMMIT;
