-- ============================================================================
-- Skill 公开/私有可见性授权迁移
-- 新增 skill_manage.visibility 列 + skill_visible_grant 授权表
-- 目标数据库: openGauss(PostgreSQL 兼容), 风格对齐 V20260728.3__skill_baseline_all_in_one.sql
-- ============================================================================

-- 1. skill_manage 加可见性列(存量默认 PUBLIC,零迁移)
ALTER TABLE skill_manage
  ADD COLUMN visibility VARCHAR(16) NOT NULL DEFAULT 'PUBLIC';

COMMENT ON COLUMN skill_manage.visibility IS '可见性: PUBLIC=公开(所有可见) / PRIVATE=私有(owner+授权可见)';

-- 2. skill_visible_grant -- 私有可见性授权表(无审批, owner 直接授权)
CREATE TABLE IF NOT EXISTS skill_visible_grant (
  id          BIGSERIAL PRIMARY KEY,
  skill_id    BIGINT      NOT NULL,
  grant_type  VARCHAR(16) NOT NULL,  -- USER | DEPARTMENT | GROUP
  target_id   VARCHAR(64) NOT NULL,  -- USER=统一认证号 / DEPARTMENT=部门名 / GROUP=统计组名
  granted_by  VARCHAR(64) NOT NULL,  -- 授权人(owner)
  created_at  TIMESTAMP   NOT NULL DEFAULT now()
);

COMMENT ON TABLE  skill_visible_grant IS 'Skill 私有可见性授权表(无审批, owner 直接授权)';
COMMENT ON COLUMN skill_visible_grant.skill_id IS 'Skill ID(skill_manage.id)';
COMMENT ON COLUMN skill_visible_grant.grant_type IS '授权对象类型: USER(人) / DEPARTMENT(部门) / GROUP(小组=统计组)';
COMMENT ON COLUMN skill_visible_grant.target_id IS '授权对象: 统一认证号 / 部门名 / 统计组名';
COMMENT ON COLUMN skill_visible_grant.granted_by IS '授权人(owner)';
COMMENT ON COLUMN skill_visible_grant.created_at IS '授权时间';

CREATE UNIQUE INDEX IF NOT EXISTS uk_skill_grant ON skill_visible_grant(skill_id, grant_type, target_id);
CREATE INDEX IF NOT EXISTS idx_skill_grant_skill ON skill_visible_grant(skill_id);