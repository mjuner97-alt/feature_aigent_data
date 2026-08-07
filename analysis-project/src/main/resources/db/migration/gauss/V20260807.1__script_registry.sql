-- ============================================================================
-- V20260807.1: script_registry 表 GaussDB 基线迁移
-- ----------------------------------------------------------------------------
-- 背景: script_registry 原本只在 MySQL 上建表 (V20260806.1__script_registry.sql),
-- ScriptRegistryMapper 走 mysql 数据源 (mapper/mysql/ScriptRegistryMapper.xml).
-- 现将注册表迁到 GaussDB, ScriptRegistryMapper 改走 gauss 数据源
-- (mapper/gauss/ScriptRegistryMapper.xml), 需要在 GaussDB 上补建表 DDL + 示例数据.
--
-- 目标数据库: openGauss (PostgreSQL 兼容)
-- 与 V20260806.1 (MySQL 版) 字段对齐, 语法切 PostgreSQL:
--   - BIGSERIAL 替代 BIGINT AUTO_INCREMENT
--   - TIMESTAMP DEFAULT CURRENT_TIMESTAMP 替代 DATETIME
--   - updated_at 用触发器维护 (PG 无 ON UPDATE CURRENT_TIMESTAMP)
--   - INSERT ... ON CONFLICT 替代 ON DUPLICATE KEY UPDATE
-- ============================================================================

CREATE TABLE IF NOT EXISTS script_registry (
    id              BIGSERIAL    PRIMARY KEY,
    script_id       VARCHAR(128) NOT NULL,
    name            VARCHAR(256) NOT NULL,
    description     TEXT,
    script_path     VARCHAR(512) NOT NULL,
    datasources     VARCHAR(256) NOT NULL DEFAULT '["gauss"]',
    params_schema   TEXT,
    timeout_seconds INT          NOT NULL DEFAULT 60,
    enabled         SMALLINT     NOT NULL DEFAULT 1,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by      VARCHAR(64),
    CONSTRAINT uk_script_registry_script_id UNIQUE (script_id)
);

CREATE INDEX IF NOT EXISTS idx_script_registry_enabled
    ON script_registry (enabled);

-- ----------------------------------------------------------------------------
-- updated_at 自动维护触发器 (PG 无 ON UPDATE CURRENT_TIMESTAMP, 用触发器模拟)
-- ----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION fn_script_registry_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_script_registry_updated_at ON script_registry;

CREATE TRIGGER trg_script_registry_updated_at
    BEFORE UPDATE ON script_registry
    FOR EACH ROW
    EXECUTE FUNCTION fn_script_registry_updated_at();

-- ----------------------------------------------------------------------------
-- 示例数据: Q2-1 指标计算 (GaussDB 单数据源)
-- 脚本内部完成: SQL 取数 + pandas 算 总数/已打分/达标数
-- ----------------------------------------------------------------------------
INSERT INTO script_registry (script_id, name, description, script_path, datasources, params_schema, timeout_seconds, created_by) VALUES
(
  'q2_1_metrics_by_dept_version',
  '部门+版本 Q2-1 指标计算',
  '按开发部门 + 版本计划筛选宽表, 自动取最新 in_date, 用 pandas 算 总数/已打分/达标数. 一次调用拿到全部数字, 替代 sql_registry_exec + python_exec 两步走.',
  'q2_1_metrics_by_dept_version.py',
  '["gauss"]',
  '[
    {"name":"dept","type":"string","required":true,"description":"开发部门, 如 杭州开发二部"},
    {"name":"version","type":"string","required":true,"description":"版本计划, 如 2026年7月份版本"}
  ]',
  60,
  'flyway'
)
ON CONFLICT (script_id) DO UPDATE SET
    name            = EXCLUDED.name,
    description     = EXCLUDED.description,
    script_path     = EXCLUDED.script_path,
    datasources     = EXCLUDED.datasources,
    params_schema   = EXCLUDED.params_schema,
    timeout_seconds = EXCLUDED.timeout_seconds;
