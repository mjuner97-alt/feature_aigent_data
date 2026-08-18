-- Q2-1 business shaping lives in registered SQL. Java only parses the generic JSON envelope.
INSERT INTO sql_registry
    (sql_id, name, description, datasource, sql_template, params_schema, created_by)
VALUES
(
  'q2_1_report_by_dept_version',
  '部门+版本 Q2-1 报告 JSON 信封',
  '返回 variables_json + summary_json。SQL 负责业务计算和展示变量组装，presentation_render 不包含 Q2-1 专用逻辑。',
  'gauss',
  'WITH base_departments AS (
     SELECT ''杭州开发一部'' AS department, 1 AS sort_no
     UNION ALL SELECT ''杭州开发二部'', 2
     UNION ALL SELECT ''杭州开发三部'', 3
     UNION ALL SELECT ''杭州开发四部'', 4
     UNION ALL SELECT ''杭州开发五部'', 5
     UNION ALL SELECT ''杭州服务支持部'', 6
     UNION ALL SELECT ''杭州技术部'', 7
     UNION ALL SELECT ''云计算实验室'', 8
     UNION ALL SELECT ''杭州产品部'', 9
   ), departments AS (
     SELECT department, sort_no FROM base_departments
     UNION ALL
     SELECT CAST(:dept AS VARCHAR), 999
     WHERE NOT EXISTS (SELECT 1 FROM base_departments WHERE department = :dept)
   ), metric AS (
     SELECT
       CAST(COUNT(*) AS BIGINT) AS total,
       CAST(COALESCE(SUM(CASE WHEN score_status_2_1 = ''已打分'' THEN 1 ELSE 0 END), 0) AS BIGINT) AS scored,
       CAST(COALESCE(SUM(CASE WHEN standard_is_2_1 = ''达标'' THEN 1 ELSE 0 END), 0) AS BIGINT) AS passed
     FROM remote_app.dsqa_dwd_req_item_app_portrait_wide_inf
     WHERE dev_dept = :dept
       AND version_plan = :version
       AND in_date = (
         SELECT MAX(in_date) FROM remote_app.dsqa_dwd_req_item_app_portrait_wide_inf
       )
   ), rates AS (
     SELECT total, scored, passed,
       ROUND(CASE WHEN total = 0 THEN 0 ELSE scored * 100.0 / total END, 2) AS scored_rate,
       ROUND(CASE WHEN total = 0 THEN 0 ELSE passed * 100.0 / total END, 2) AS passed_rate
     FROM metric
   ), record_rows AS (
     SELECT d.sort_no,
       CASE WHEN d.department = :dept THEN
         json_build_object(
           ''department'', d.department,
           ''version'', CAST(:version AS VARCHAR),
           ''total'', r.total,
           ''scored'', r.scored,
           ''passed'', r.passed,
           ''scoredPctText'', to_char(r.scored_rate, ''FM999999990.00'') || ''%'',
           ''passedPctText'', to_char(r.passed_rate, ''FM999999990.00'') || ''%''
         )
       ELSE
         json_build_object(
           ''department'', d.department,
           ''version'', ''-'',
           ''total'', ''-'',
           ''scored'', ''-'',
           ''passed'', ''-'',
           ''scoredPctText'', ''-'',
           ''passedPctText'', ''-''
         )
       END AS record_json
     FROM departments d CROSS JOIN rates r
   ), records AS (
     SELECT json_agg(record_json) AS records_json
     FROM (SELECT record_json FROM record_rows ORDER BY sort_no) ordered_records
   )
   SELECT
     json_build_object(
       ''title'', CAST(:dept AS VARCHAR) || '' '' || CAST(:version AS VARCHAR) || '' Q2-1 指标报告'',
       ''versions'', json_build_array(CAST(:version AS VARCHAR)),
       ''scoredRates'', json_build_array(r.scored_rate),
       ''passedRates'', json_build_array(r.passed_rate),
       ''records'', records.records_json,
       ''dataSource'', ''GaussDB remote_app.dsqa_dwd_req_item_app_portrait_wide_inf''
     ) AS "variables_json",
     json_build_object(
       ''department'', CAST(:dept AS VARCHAR),
       ''version'', CAST(:version AS VARCHAR),
       ''total'', r.total,
       ''scored'', r.scored,
       ''passed'', r.passed,
       ''scoredPctText'', to_char(r.scored_rate, ''FM999999990.00'') || ''%'',
       ''passedPctText'', to_char(r.passed_rate, ''FM999999990.00'') || ''%''
     ) AS "summary_json"
   FROM rates r CROSS JOIN records',
  '[
    {"name":"dept","type":"string","required":true,"description":"开发部门，如 杭州开发二部"},
    {"name":"version","type":"string","required":true,"description":"完整版本名称，如 2026年7月份版本"}
  ]',
  'flyway'
)
ON DUPLICATE KEY UPDATE
    name = VALUES(name),
    description = VALUES(description),
    datasource = VALUES(datasource),
    sql_template = VALUES(sql_template),
    params_schema = VALUES(params_schema);

UPDATE presentation_template_registry
SET data_adapter = 'json-envelope-v1'
WHERE template_id = 'q2_1_by_dept_version_metrics/report-v1';
