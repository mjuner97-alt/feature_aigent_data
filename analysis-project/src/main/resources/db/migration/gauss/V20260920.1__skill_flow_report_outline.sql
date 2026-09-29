-- 长任务自定义报告大纲:流程定义保存大纲,执行创建时深拷贝为快照(改编排不影响历史报告重建)
ALTER TABLE skill_flow
    ADD COLUMN report_outline TEXT NULL;

COMMENT ON COLUMN skill_flow.report_outline
    IS '报告大纲 JSON:{numbering:{level1,level2,level3},items:[{id,title,level,nodeKey,children}]};NULL=未配置,走按节点顺序拼接的兼容逻辑';

ALTER TABLE skill_flow_execution
    ADD COLUMN report_outline_snapshot TEXT NULL;

COMMENT ON COLUMN skill_flow_execution.report_outline_snapshot
    IS '执行创建时的大纲快照(深拷贝自 skill_flow.report_outline);报告生成/重建只用快照,避免流程编辑后历史报告漂移';
