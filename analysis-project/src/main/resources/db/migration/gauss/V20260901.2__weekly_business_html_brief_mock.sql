-- Mock script used by the weekly business HTML brief rendering smoke test.
INSERT INTO script_registry (script_id, name, description, script_path, datasources, params_schema, timeout_seconds, enabled, created_by)
SELECT 'weekly_business_html_brief_mock',
       '每周经营数据 HTML 快报（Mock）',
       '使用固定 Mock 数据输出 Markdown 与两个 ECharts 图表块，不连接数据库，用于验证 script_exec/SSE/HTML 渲染链路。',
       'weekly_business_html_brief_mock.py',
       '[]',
       '[{"name":"weeks","type":"integer","required":false,"description":"周数，1-8，默认4"},{"name":"business_line","type":"string","required":false,"description":"业务范围，默认全部业务线"}]',
       30, 1, 'flyway'
WHERE NOT EXISTS (SELECT 1 FROM script_registry WHERE script_id = 'weekly_business_html_brief_mock');
