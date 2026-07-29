-- ============================================================================
-- V20260729.2: sql_registry - 移除 :limit 参数, 改由工具内部固定 10000
-- ----------------------------------------------------------------------------
-- 之前 V20260729.1 录入的 SQL 模板末尾含 `LIMIT :limit` 占位符, 导致两个问题:
-- 1. LLM 不传 limit 时 Spring NamedParameterUtils 报 "No value supplied for 'limit'"
-- 2. LLM 传 limit 时 SqlRegistryExecTool.ensureLimit 又追加 `LIMIT 10000`,
--    形成 `LIMIT ? LIMIT 10000` 在 GaussDB/openGauss 报 syntax error
--
-- 修复: 模板里去掉 `:limit` 占位符, 让工具内部 ROW_LIMIT=10000 通过 ensureLimit 自动追加.
-- params_schema 也去掉 limit 参数, LLM 不能再传 (业务上也没必要, 10000 行兜底足够).
-- ============================================================================

UPDATE sql_registry SET
  sql_template = 'SELECT
     projectzh_no AS 项目编号,
     projectzh_name AS 项目名称,
     dev_dept AS 开发部门,
     version_plan AS 版本计划,
     app AS 涉及应用,
     product_line AS 产品线,
     stat_group AS 统计组,
     score_status_2_1 AS Q2_1打分状态,
     standard_is_2_1 AS Q2_1是否达标
   FROM dsqa_dwd_req_item_app_portrait_wide_inf
   WHERE dev_dept = :dept
     AND version_plan = :version
     AND in_date = (SELECT MAX(in_date) FROM dsqa_dwd_req_item_app_portrait_wide_inf)',
  params_schema = '[
    {"name":"dept","type":"string","required":true,"description":"开发部门, 如 杭州开发二部"},
    {"name":"version","type":"string","required":true,"description":"版本计划, 如 2026年7月份版本"}
  ]',
  updated_at = CURRENT_TIMESTAMP
WHERE sql_id = 'q2_1_metrics_by_dept_version';

UPDATE sql_registry SET
  sql_template = 'SELECT
     userId,
     count() AS 会话数,
     avg(totalDurationMs) AS 平均时长ms,
     avg(eventCount) AS 平均事件数,
     sumIf(1, status = ''COMPLETED'') AS 完成数
   FROM default.trace_recent
   WHERE userId = :userId
     AND createdAt >= :startTime
   GROUP BY userId',
  params_schema = '[
    {"name":"userId","type":"string","required":true,"description":"用户 ID, 如 alice"},
    {"name":"startTime","type":"date","required":true,"description":"开始日期 ISO 格式 YYYY-MM-DD, 如 2026-07-01"}
  ]',
  updated_at = CURRENT_TIMESTAMP
WHERE sql_id = 'trace_recent_stats_by_user';
