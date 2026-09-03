-- ============================================================================
-- Skill 可见性三态(公开/私有/个人) + 虚拟组支持
--
-- 规则:
--   个人(PERSONAL)= 全员可见,默认仅创建者使用,他人需引用(新建默认值)
--   公开(PUBLIC)  = 全员可见;选择小组/部门/公司等维度发布,需审批,审批通过后维度内默认使用
--   私有(PRIVATE) = 仅 owner + 指定用户/部门/小组/虚拟组授权可见,不走审批,授权即时生效
--
-- 本迁移:
--   1. 新建 skill_virtual_group(虚拟组:组名+userid),供私有授权按虚拟组命中
--   2. 补建 skill_visible_grant 的 DDL(该表此前由手工建表,未进迁移;IF NOT EXISTS 幂等)
-- ============================================================================

-- 1. skill_virtual_group -- 虚拟组成员表
-- 一行 = 一个组的一个成员;组的定义由同名行集合表达(组名+userid)。
CREATE TABLE IF NOT EXISTS skill_virtual_group (
  id          BIGSERIAL PRIMARY KEY,
  group_name  VARCHAR(128) NOT NULL,
  user_id     VARCHAR(64) NOT NULL,
  created_by  VARCHAR(64),
  created_at  TIMESTAMP NOT NULL DEFAULT now()
);

COMMENT ON TABLE skill_virtual_group IS '虚拟组成员表(组名+userid),私有Skill授权可按虚拟组命中';
COMMENT ON COLUMN skill_virtual_group.id IS '主键ID';
COMMENT ON COLUMN skill_virtual_group.group_name IS '虚拟组名(组的唯一标识)';
COMMENT ON COLUMN skill_virtual_group.user_id IS '成员统一认证号';
COMMENT ON COLUMN skill_virtual_group.created_by IS '建组/加成员操作人';
COMMENT ON COLUMN skill_virtual_group.created_at IS '创建时间';

CREATE UNIQUE INDEX uk_virtual_group_member ON skill_virtual_group(group_name, user_id);
CREATE INDEX idx_virtual_group_user ON skill_virtual_group(user_id);

-- 2. skill_visible_grant -- 私有可见性授权表(补建 DDL,幂等)
CREATE TABLE IF NOT EXISTS skill_visible_grant (
  id          BIGSERIAL PRIMARY KEY,
  skill_id    BIGINT NOT NULL,
  grant_type  VARCHAR(32) NOT NULL,
  target_id   VARCHAR(128) NOT NULL,
  granted_by  VARCHAR(64),
  created_at  TIMESTAMP NOT NULL DEFAULT now()
);

COMMENT ON TABLE skill_visible_grant IS 'Skill私有可见性授权表';
COMMENT ON COLUMN skill_visible_grant.skill_id IS 'Skill ID';
COMMENT ON COLUMN skill_visible_grant.grant_type IS '授权类型: USER/DEPARTMENT/GROUP/VIRTUAL_GROUP';
COMMENT ON COLUMN skill_visible_grant.target_id IS '授权目标: USER=统一认证号 / DEPARTMENT=部门名 / GROUP=统计组名 / VIRTUAL_GROUP=虚拟组名';
COMMENT ON COLUMN skill_visible_grant.granted_by IS '授权人(owner)';
COMMENT ON COLUMN skill_visible_grant.created_at IS '创建时间';

CREATE UNIQUE INDEX uk_visible_grant_target ON skill_visible_grant(skill_id, grant_type, target_id);
