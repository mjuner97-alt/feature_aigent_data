-- Bind presentation templates to registered SQL so large result sets remain server-side.
ALTER TABLE presentation_template_registry
    ADD COLUMN data_provider_type VARCHAR(16) NOT NULL DEFAULT 'inline';
ALTER TABLE presentation_template_registry
    ADD COLUMN data_provider_id VARCHAR(160);
ALTER TABLE presentation_template_registry
    ADD COLUMN data_adapter VARCHAR(160);
ALTER TABLE presentation_template_registry
    ADD COLUMN parameter_mapping TEXT NOT NULL DEFAULT '{}';

INSERT INTO sql_registry
    (sql_id, name, description, datasource, sql_template, params_schema, created_by)
VALUES
(
  'q2_1_report_by_dept_version',
  '部门+版本 Q2-1 报告聚合查询',
  '按开发部门和版本聚合 Q2-1 总数、已打分数、达标数，直接供展示模板使用，避免传输项目级明细。',
  'gauss',
  'SELECT
     :dept AS "department",
     :version AS "version",
     COUNT(*) AS "total",
     COALESCE(SUM(CASE WHEN score_status_2_1 = ''已打分'' THEN 1 ELSE 0 END), 0) AS "scored",
     COALESCE(SUM(CASE WHEN standard_is_2_1 = ''达标'' THEN 1 ELSE 0 END), 0) AS "passed"
   FROM remote_app.dsqa_dwd_req_item_app_portrait_wide_inf
   WHERE dev_dept = :dept
     AND version_plan = :version
     AND in_date = (
       SELECT MAX(in_date) FROM remote_app.dsqa_dwd_req_item_app_portrait_wide_inf
     )',
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
SET data_provider_type = 'sql',
    data_provider_id = 'q2_1_report_by_dept_version',
    data_adapter = 'rows-v1',
    parameter_mapping = '{}'
WHERE template_id = 'q2_1_by_dept_version_metrics/report-v1';
