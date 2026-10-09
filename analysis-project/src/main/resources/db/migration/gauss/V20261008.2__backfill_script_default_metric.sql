-- Existing Python scripts predate the default dependency assigned on create/update.
-- Preserve any explicitly configured default dependencies.
UPDATE script_registry AS script
SET default_metric_ids = CAST(metric.id AS VARCHAR(512))
FROM skill_dependency_metric AS metric
WHERE metric.code = 'default_metric'
  AND (script.default_metric_ids IS NULL OR TRIM(script.default_metric_ids) = '');
