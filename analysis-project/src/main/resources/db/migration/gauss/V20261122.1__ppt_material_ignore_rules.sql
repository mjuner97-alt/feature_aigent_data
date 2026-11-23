ALTER TABLE digital_qa_ppt_material_batch ADD COLUMN IF NOT EXISTS material_type VARCHAR(512);
COMMENT ON COLUMN digital_qa_ppt_material_batch.material_type IS '材料类型名称，用于匹配材料类型忽略规则';

CREATE TABLE IF NOT EXISTS digital_qa_ppt_material_ignore_rule (
    id BIGSERIAL PRIMARY KEY,
    material_type VARCHAR(512) NOT NULL,
    rule_type VARCHAR(32) NOT NULL,
    rule_value VARCHAR(512) NOT NULL,
    rule_scope VARCHAR(32) NOT NULL DEFAULT 'PAGE',
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_ppt_material_ignore_rule UNIQUE(material_type, rule_type, rule_value, rule_scope)
);

COMMENT ON TABLE digital_qa_ppt_material_ignore_rule IS '例会材料类型对应的页面忽略规则';
COMMENT ON COLUMN digital_qa_ppt_material_ignore_rule.id IS '忽略规则主键';
COMMENT ON COLUMN digital_qa_ppt_material_ignore_rule.material_type IS '关联的材料类型名称';
COMMENT ON COLUMN digital_qa_ppt_material_ignore_rule.rule_type IS '规则类型，KEYWORD表示关键词，CHAPTER表示章节编号';
COMMENT ON COLUMN digital_qa_ppt_material_ignore_rule.rule_value IS '关键词文本或章节编号';
COMMENT ON COLUMN digital_qa_ppt_material_ignore_rule.rule_scope IS '忽略范围，PAGE表示当前页，SECTION表示整个章节';
COMMENT ON COLUMN digital_qa_ppt_material_ignore_rule.enabled IS '是否启用规则';
COMMENT ON COLUMN digital_qa_ppt_material_ignore_rule.created_at IS '创建时间';
COMMENT ON COLUMN digital_qa_ppt_material_ignore_rule.updated_at IS '更新时间';
