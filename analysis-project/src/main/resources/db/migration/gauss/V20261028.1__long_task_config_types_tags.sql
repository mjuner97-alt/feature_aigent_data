-- 长任务配置域：与 skill_flow 一一对应的类型、标签及绑定关系。
CREATE TABLE IF NOT EXISTS skill_flow_type (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(128) NOT NULL,
    description VARCHAR(512),
    sort_order INTEGER NOT NULL DEFAULT 0,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_skill_flow_type_name UNIQUE (name),
    CONSTRAINT ck_skill_flow_type_sort_order CHECK (sort_order >= 0)
);
COMMENT ON TABLE skill_flow_type IS '长任务流程类型字典；类型排序值用于自动触发时的跨类型顺序';
COMMENT ON COLUMN skill_flow_type.id IS '类型主键';
COMMENT ON COLUMN skill_flow_type.name IS '类型名称；全局唯一且不能为空';
COMMENT ON COLUMN skill_flow_type.description IS '类型说明';
COMMENT ON COLUMN skill_flow_type.sort_order IS '自动触发顺序；数值越小越优先';
COMMENT ON COLUMN skill_flow_type.enabled IS '是否启用；停用后不可绑定新流程且不参与自动触发';
COMMENT ON COLUMN skill_flow_type.created_at IS '创建时间';
COMMENT ON COLUMN skill_flow_type.updated_at IS '最后更新时间';

CREATE TABLE IF NOT EXISTS skill_flow_tag (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(128) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_skill_flow_tag_name UNIQUE (name)
);
COMMENT ON TABLE skill_flow_tag IS '长任务流程标签字典；标签仅用于筛选和检索，不参与调度排序';
COMMENT ON COLUMN skill_flow_tag.id IS '标签主键';
COMMENT ON COLUMN skill_flow_tag.name IS '标签名称；全局唯一且不能为空';
COMMENT ON COLUMN skill_flow_tag.created_at IS '创建时间';
COMMENT ON COLUMN skill_flow_tag.updated_at IS '最后更新时间';

ALTER TABLE skill_flow ADD COLUMN IF NOT EXISTS type_id BIGINT;
ALTER TABLE skill_flow ADD COLUMN IF NOT EXISTS priority INTEGER NOT NULL DEFAULT 999999;
COMMENT ON COLUMN skill_flow.type_id IS '长任务流程类型；可为空以兼容历史流程，非空时必须关联 skill_flow_type';
COMMENT ON COLUMN skill_flow.priority IS '同一类型内的自动执行优先级；数值越小越优先，历史流程默认 999999';
ALTER TABLE skill_flow ADD CONSTRAINT fk_skill_flow_type FOREIGN KEY (type_id) REFERENCES skill_flow_type(id);
CREATE INDEX IF NOT EXISTS idx_skill_flow_type_priority ON skill_flow(type_id, priority, created_at, id);

CREATE TABLE IF NOT EXISTS skill_flow_tag_rel (
    flow_id BIGINT NOT NULL REFERENCES skill_flow(id) ON DELETE CASCADE,
    tag_id BIGINT NOT NULL REFERENCES skill_flow_tag(id) ON DELETE CASCADE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (flow_id, tag_id)
);
COMMENT ON TABLE skill_flow_tag_rel IS '长任务流程与标签的多对多绑定关系';
COMMENT ON COLUMN skill_flow_tag_rel.flow_id IS '关联 skill_flow.id';
COMMENT ON COLUMN skill_flow_tag_rel.tag_id IS '关联 skill_flow_tag.id';
COMMENT ON COLUMN skill_flow_tag_rel.created_at IS '绑定创建时间';

-- 幂等兼容迁移：确保默认类型存在，历史流程的 type_id 保持可空，不覆盖既有数据。
INSERT INTO skill_flow_type(name, description, sort_order, enabled)
SELECT '其他', '系统迁移默认类型', 999999, TRUE
WHERE NOT EXISTS (SELECT 1 FROM skill_flow_type WHERE name='其他');
