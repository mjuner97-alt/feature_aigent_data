-- 为未显式指定流程类型的长任务统一使用默认类型 1。
-- 先确保默认类型存在，再回填历史 NULL，最后设置列默认值。
INSERT INTO skill_flow_type (id, name, description, sort_order, enabled)
SELECT 1, '其他', '系统默认流程类型', 999999, TRUE
WHERE NOT EXISTS (SELECT 1 FROM skill_flow_type WHERE id = 1);

SELECT setval(
    pg_get_serial_sequence('skill_flow_type', 'id'),
    GREATEST((SELECT COALESCE(MAX(id), 1) FROM skill_flow_type), 1),
    TRUE
);

UPDATE skill_flow
SET type_id = 1
WHERE type_id IS NULL;

ALTER TABLE skill_flow
    ALTER COLUMN type_id SET DEFAULT 1;

COMMENT ON COLUMN skill_flow.type_id IS '长任务流程类型；未指定时默认关联 skill_flow_type.id=1';

-- 默认类型必须始终可绑定，且禁止被删除。
UPDATE skill_flow_type
SET enabled = TRUE,
    name = COALESCE(NULLIF(name, ''), '其他'),
    updated_at = CURRENT_TIMESTAMP
WHERE id = 1;

CREATE OR REPLACE FUNCTION prevent_default_skill_flow_type_delete()
RETURNS TRIGGER AS $$
BEGIN
    IF OLD.id = 1 THEN
        RAISE EXCEPTION 'skill_flow_type id=1 is the system default type and cannot be deleted';
    END IF;
    RETURN OLD;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_protect_default_skill_flow_type ON skill_flow_type;
CREATE TRIGGER trg_protect_default_skill_flow_type
    BEFORE DELETE ON skill_flow_type
    FOR EACH ROW
    EXECUTE PROCEDURE prevent_default_skill_flow_type_delete();

