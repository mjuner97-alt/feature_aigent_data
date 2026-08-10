-- ============================================================================
-- V20260807.2: sql_registry 表 GaussDB 基线迁移
-- ----------------------------------------------------------------------------
-- 背景: 同事的 commit 3b7e0ac "sql registry迁移" 把 SqlRegistryMapper 从 mysql
-- 包迁到 gauss 包, 但只迁了 mapper 代码, 没在 GaussDB 上建表. 导致 sql_list /
-- sql_registry_exec 调用报 "relation sql_registry does not exist on gaussdb".
--
-- 本脚本补齐 GaussDB 端建表 DDL + 示例数据.
-- 目标数据库: openGauss (PostgreSQL 兼容)
-- 与 V20260729.1 (MySQL 版) 字段对齐, 语法切 PostgreSQL.
-- ============================================================================

CREATE TABLE IF NOT EXISTS sql_registry (
    id             BIGSERIAL    PRIMARY KEY,
    sql_id         VARCHAR(64)  NOT NULL,
    name           VARCHAR(128) NOT NULL,
    description    TEXT,
    datasource     VARCHAR(16)  NOT NULL,
    sql_template   TEXT         NOT NULL,
    params_schema  TEXT,
    enabled        SMALLINT     NOT NULL DEFAULT 1,
    created_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by     VARCHAR(64),
    CONSTRAINT uk_sql_registry_sql_id UNIQUE (sql_id)
);

CREATE INDEX IF NOT EXISTS idx_sql_registry_datasource_enabled
    ON sql_registry (datasource, enabled);

-- ----------------------------------------------------------------------------
-- updated_at 自动维护触发器 (PG 无 ON UPDATE CURRENT_TIMESTAMP, 用触发器模拟)
-- ----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION fn_sql_registry_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_sql_registry_updated_at ON sql_registry;

CREATE TRIGGER trg_sql_registry_updated_at
    BEFORE UPDATE ON sql_registry
    FOR EACH ROW
    EXECUTE PROCEDURE fn_sql_registry_updated_at();

-- ----------------------------------------------------------------------------
-- 示例数据 1: Q2-1 指标查询 (GaussDB)
-- 等价于 wide_table_query + filters + subqueryFilters (取最新 in_date)
-- ----------------------------------------------------------------------------
INSERT INTO sql_registry (sql_id, name, description, datasource, sql_template, params_schema, created_by) VALUES
(
  'q2_1_metrics_by_dept_version',
  '部门+版本 Q2-1 指标查询',
  '按开发部门 + 版本计划筛选宽表, 自动取最新 in_date, 返回 Q2-1 打分状态/达标字段. 用于算 Q2-1 完成率/达标率.',
  'gauss',
  'SELECT
     projectzh_no AS "项目编号",
     projectzh_name AS "项目名称",
     dev_dept AS "开发部门",
     version_plan AS "版本计划",
     app AS "涉及应用",
     product_line AS "产品线",
     stat_group AS "统计组",
     score_status_2_1 AS "Q2_1打分状态",
     standard_is_2_1 AS "Q2_1是否达标"
   FROM dsqa_dwd_req_item_app_portrait_wide_inf
   WHERE dev_dept = :dept
     AND version_plan = :version
     AND in_date = (SELECT MAX(in_date) FROM dsqa_dwd_req_item_app_portrait_wide_inf)',
  '[
    {"name":"dept","type":"string","required":true,"description":"开发部门, 如 杭州开发二部"},
    {"name":"version","type":"string","required":true,"description":"版本计划, 如 2026年7月份版本"}
  ]',
  'flyway'
)
ON DUPLICATE KEY UPDATE
    name          = VALUES(name),
    description   = VALUES(description),
    sql_template  = VALUES(sql_template),
    params_schema = VALUES(params_schema);

-- ----------------------------------------------------------------------------
-- 示例数据 2: 用户会话统计 (ClickHouse)
-- 演示 GROUP BY + 聚合函数 + sumIf + 时间范围筛选
-- ----------------------------------------------------------------------------
INSERT INTO sql_registry (sql_id, name, description, datasource, sql_template, params_schema, created_by) VALUES
(
  'trace_recent_stats_by_user',
  '用户会话统计',
  '按 userId 分组, 取会话数/平均时长/平均事件数/完成数. 支持时间范围筛选.',
  'clickhouse',
  'SELECT
     userId,
     count() AS "会话数",
     avg(totalDurationMs) AS "平均时长ms",
     avg(eventCount) AS "平均事件数",
     sumIf(1, status = ''COMPLETED'') AS "完成数"
   FROM default.trace_recent
   WHERE userId = :userId
     AND createdAt >= :startTime
   GROUP BY userId',
  '[
    {"name":"userId","type":"string","required":true,"description":"用户 ID, 如 alice"},
    {"name":"startTime","type":"date","required":true,"description":"开始日期 ISO 格式 YYYY-MM-DD, 如 2026-07-01"}
  ]',
  'flyway'
)
ON DUPLICATE KEY UPDATE
    name          = VALUES(name),
    description   = VALUES(description),
    sql_template  = VALUES(sql_template),
    params_schema = VALUES(params_schema);
