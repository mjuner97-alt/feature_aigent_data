-- GaussDB：内置动态参数模拟脚本，仅注册元数据，不创建或运行 Python 文件。
-- 可重复运行；已存在的同名脚本保持原样，不覆盖用户修改。
INSERT INTO script_registry
    (script_id, name, description, script_path, datasources, params_schema,
     timeout_seconds, enabled, created_by, default_metric_ids)
SELECT
    'dynamic_params_version_year_mock',
    '动态参数测试（版本数组、年份单值）',
    '内置模拟脚本：versions 为版本数组，year 为年份单值。默认自动计算最近三个完整月份和当前年。按版本返回模拟订单数和销售额，并回显实际接收参数；不执行 Python，不查询业务数据。',
    'skill_demo_u001/dynamic_params_version_year_mock.py',
    '["gauss"]',
    '[{"name":"versions","type":"string[]","required":true,"description":"版本数组，默认自动计算最近三个完整月份；可在动态时间中修改周期数量和范围。","default":{"$dynamic":{"v":1,"kind":"period_window","unit":"month","startOffset":-3,"endOffset":-1,"format":"{year}年{month}月份版本","order":"asc","output":"list"}}},{"name":"year","type":"string","required":true,"description":"年份单值，默认自动计算当前年，例如 2026年；允许改为直接填写。","default":{"$dynamic":{"v":1,"kind":"period_window","unit":"year","startOffset":0,"endOffset":0,"format":"{year}年","order":"asc","output":"single"}}}]',
    60, 1, 'skill_demo_u001', NULL
WHERE NOT EXISTS (
    SELECT 1 FROM script_registry WHERE script_id = 'dynamic_params_version_year_mock'
);
