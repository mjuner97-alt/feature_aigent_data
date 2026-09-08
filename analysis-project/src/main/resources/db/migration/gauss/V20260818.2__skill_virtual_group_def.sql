-- ============================================================================
-- 虚拟组加组头表 skill_virtual_group_def
--
-- 背景:原模型"组 = 成员行集合"导致空组无法存在——建组若不带首个成员则什么都不写,
--       管理页建完组列表仍是空的,没有"加成员"入口;移除最后一个成员等于删组。
-- 修复:组的定义独立成表(一行 = 一个组),成员表只存成员。空组合法存在。
--
-- 本迁移:
--   1. 新建 skill_virtual_group_def(组头表:组名主键)
--   2. 从 skill_virtual_group 回填已有组名(按组名 DISTINCT,空表时无操作)
-- ============================================================================
CREATE TABLE IF NOT EXISTS skill_virtual_group_def (
  group_name  VARCHAR(128) PRIMARY KEY,
  created_by  VARCHAR(64),
  created_at  TIMESTAMP NOT NULL DEFAULT now()
);

COMMENT ON TABLE skill_virtual_group_def IS '虚拟组定义表(组头),一行=一个虚拟组;成员在 skill_virtual_group';
COMMENT ON COLUMN skill_virtual_group_def.group_name IS '虚拟组名(组的唯一标识)';
COMMENT ON COLUMN skill_virtual_group_def.created_by IS '建组人';
COMMENT ON COLUMN skill_virtual_group_def.created_at IS '创建时间';

-- 回填:成员表里已出现的组名补进组头表(幂等,重复执行不重复插)
INSERT INTO skill_virtual_group_def (group_name, created_by, created_at)
SELECT g.group_name, MIN(g.created_by), MIN(g.created_at)
FROM skill_virtual_group g
WHERE NOT EXISTS (
  SELECT 1 FROM skill_virtual_group_def d WHERE d.group_name = g.group_name
)
GROUP BY g.group_name;
