-- Skill 虚拟组表。
-- Skill 列表的私有授权可见性查询会引用 skill_virtual_group；缺表会使 /api/skills 返回 500。
-- 本迁移仅补齐缺失表，适用于已有 Skill 数据的库。

CREATE TABLE IF NOT EXISTS skill_virtual_group_def (
    group_name VARCHAR(128) PRIMARY KEY,
    created_by VARCHAR(64) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS skill_virtual_group (
    id BIGSERIAL PRIMARY KEY,
    group_name VARCHAR(128) NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    created_by VARCHAR(64) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_skill_virtual_group_member UNIQUE (group_name, user_id)
);

CREATE INDEX IF NOT EXISTS idx_skill_virtual_group_user
    ON skill_virtual_group(user_id);
