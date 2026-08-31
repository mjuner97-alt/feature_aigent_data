-- =====================================================================
-- SQL 注册表 - 预审 SQL 模板, 通过 sql_id 调用执行
-- 业务方/DBA 录入, LLM 只能传 sql_id + params, 不能改 SQL 结构
-- =====================================================================
-- 注意: 此文件仅作文档保留. 实际建表 + 示例数据由 Flyway 迁移执行:
--   src/main/resources/db/migration/V20260729.1__sql_registry.sql  (建表 + 2 条示例)
--   src/main/resources/db/migration/V20260729.2__sql_registry_drop_limit_param.sql  (移除 :limit 参数)
-- 新增 sql_registry 记录请走 Flyway 迁移, 不要手工 INSERT.
-- =====================================================================

-- DDL (与 V20260729.1 一致, 此处保留作文档参考)
CREATE TABLE IF NOT EXISTS sql_registry (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    sql_id VARCHAR(64) NOT NULL UNIQUE COMMENT '业务可读 ID, snake_case, 如 req_sign_status_by_item',
    name VARCHAR(128) NOT NULL COMMENT '中文名称',
    description TEXT COMMENT '用途说明',
    datasource VARCHAR(16) NOT NULL COMMENT '目标数据源: mysql / gauss / clickhouse',
    sql_template TEXT NOT NULL COMMENT 'SQL 模板, :param 命名占位符 (不要写 LIMIT :limit, 工具内部固定 10000 兜底)',
    params_schema JSON COMMENT '参数定义 JSON: [{"name":"...","type":"...","required":bool,"description":"..."}]',
    enabled TINYINT NOT NULL DEFAULT 1 COMMENT '0=禁用 1=启用',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    created_by VARCHAR(64) COMMENT '创建人',
    INDEX idx_datasource_enabled (datasource, enabled)
) ENGINE=InnoDB CHARSET=utf8mb4 COMMENT='SQL 配置注册表';

-- =====================================================================
-- 示例数据 1: Q2-1 指标查询 (GaussDB)
-- 模板末尾不写 LIMIT, 工具内部固定 10000 兜底 (防 LLM 传 limit 触发重复 LIMIT 语法错)
-- =====================================================================
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
  'dba'
)
ON DUPLICATE KEY UPDATE name=VALUES(name), description=VALUES(description),
                        sql_template=VALUES(sql_template), params_schema=VALUES(params_schema);

-- =====================================================================
-- 示例数据 2: 用户会话统计 (ClickHouse)
-- 演示 GROUP BY + 聚合函数 + sumIf + 时间范围筛选
-- =====================================================================
INSERT INTO sql_registry (sql_id, name, description, datasource, sql_template, params_schema, created_by) VALUES
(
  'trace_recent_stats_by_user',
  '用户会话统计',
  '按 userId 分组, 取会话数/平均时长/平均事件数/完成数. 支持时间范围筛选.',
  'clickhouse',
  'SELECT
     userId,
     count() AS `会话数`,
     avg(totalDurationMs) AS `平均时长ms`,
     avg(eventCount) AS `平均事件数`,
     sumIf(1, status = ''COMPLETED'') AS `完成数`
   FROM default.trace_recent
   WHERE userId = :userId
     AND createdAt >= :startTime
   GROUP BY userId',
  '[
    {"name":"userId","type":"string","required":true,"description":"用户 ID, 如 alice"},
    {"name":"startTime","type":"date","required":true,"description":"开始日期 ISO 格式 YYYY-MM-DD, 如 2026-07-01"}
  ]',
  'dba'
)
ON DUPLICATE KEY UPDATE name=VALUES(name), description=VALUES(description),
                        sql_template=VALUES(sql_template), params_schema=VALUES(params_schema);

-- =====================================================================
-- 验证查询 (运维执行, 不影响数据)
-- =====================================================================
-- SELECT sql_id, name, datasource, enabled FROM sql_registry ORDER BY sql_id;
