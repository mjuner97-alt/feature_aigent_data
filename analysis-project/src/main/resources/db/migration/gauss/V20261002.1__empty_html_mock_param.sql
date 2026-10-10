-- 为内置空 HTML 模拟脚本增加一个用于验证长任务参数规则的单值参数。
INSERT INTO script_registry (script_id, name, description, script_path, datasources, params_schema, timeout_seconds, enabled, created_by)
SELECT 'empty_html_flow_demo', '空 HTML 参数演示', '用于验证长任务参数固定值和预设规则', 'demo/empty_html_flow_demo.py', '["gauss"]',
       '[{"name":"demo_version","type":"string","required":false,"description":"单值演示版本，例如 2026年4月份版本"},{"name":"demo_versions","type":"string[]","required":false,"description":"多值演示版本，可选择近三个月份版本"}]', 60, 1, 'system'
WHERE NOT EXISTS (SELECT 1 FROM script_registry WHERE script_id = 'empty_html_flow_demo');

UPDATE script_registry
SET params_schema = '[{"name":"demo_version","type":"string","required":false,"description":"单值演示版本，例如 2026年4月份版本"},{"name":"demo_versions","type":"string[]","required":false,"description":"多值演示版本，可选择近三个月份版本"}]',
    enabled = 1
WHERE script_id = 'empty_html_flow_demo';
