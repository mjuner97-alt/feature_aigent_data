-- ============================================================================
-- V20260729.3: sql_registry - 中文别名加引号 (ClickHouse 反引号 + GaussDB 双引号)
-- ----------------------------------------------------------------------------
-- V20260729.2 后 LLM 调 sql_registry_exec(trace_recent_stats_by_user) 报:
--   Code: 62. DB::Exception: Unrecognized token: Syntax error: failed at position 37 (会)
-- 原因: ClickHouse 不接受未引用的非 ASCII 标识符, 必须用反引号包裹: `AS `会话数``
-- GaussDB (openGauss/PostgreSQL) 同理需要双引号: AS "项目编号"
-- 修复后中文别名仍作为列 label 返回 (不带引号), pandas 用 df['会话数'] 仍可访问.
-- ============================================================================

UPDATE sql_registry SET
  sql_template = 'SELECT
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
  updated_at = CURRENT_TIMESTAMP
WHERE sql_id = 'q2_1_metrics_by_dept_version';

UPDATE sql_registry SET
  sql_template = 'SELECT
     userId,
     count() AS `会话数`,
     avg(totalDurationMs) AS `平均时长ms`,
     avg(eventCount) AS `平均事件数`,
     sumIf(1, status = ''COMPLETED'') AS `完成数`
   FROM default.trace_recent
   WHERE userId = :userId
     AND createdAt >= :startTime
   GROUP BY userId',
  updated_at = CURRENT_TIMESTAMP
WHERE sql_id = 'trace_recent_stats_by_user';
