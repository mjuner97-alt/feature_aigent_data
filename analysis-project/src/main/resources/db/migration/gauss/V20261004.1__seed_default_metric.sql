-- Demo fallback metric used when a new Skill or Python script has no explicit dependencies.
INSERT INTO skill_dependency_metric
    (code, name, description, enabled, notify_enabled, notify_content_type)
SELECT 'default_metric', '默认指标', '系统演示用默认指标；未选择依赖指标时作为初始回填值。', TRUE, FALSE, 'TEXT'
WHERE NOT EXISTS (
    SELECT 1 FROM skill_dependency_metric WHERE code = 'default_metric'
);

-- Additional demo choices for the dependency selector. Re-running this file is safe.
INSERT INTO skill_dependency_metric
    (code, name, description, enabled, notify_enabled, notify_content_type)
SELECT seed.code, seed.name, seed.description, TRUE, FALSE, 'TEXT'
FROM (VALUES
    ('source_data_ready', '源数据就绪', '模拟源系统当日数据已完成同步。'),
    ('quality_check_passed', '质量校验通过', '模拟当日数据质量检查已通过。'),
    ('daily_report_ready', '日报数据就绪', '模拟日报所需的汇总数据已生成。')
) AS seed(code, name, description)
WHERE NOT EXISTS (
    SELECT 1 FROM skill_dependency_metric metric WHERE metric.code = seed.code
);
