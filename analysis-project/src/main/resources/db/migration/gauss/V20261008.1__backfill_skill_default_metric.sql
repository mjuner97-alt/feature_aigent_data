-- Give existing Skills with no default dependency the seeded default metric.
-- Existing selections are preserved; if the metric is absent, nothing changes.
UPDATE skill_manage AS skill
SET default_metric_ids = CAST(metric.id AS VARCHAR(512))
FROM skill_dependency_metric AS metric
WHERE metric.code = 'default_metric'
  AND (skill.default_metric_ids IS NULL OR TRIM(skill.default_metric_ids) = '');
