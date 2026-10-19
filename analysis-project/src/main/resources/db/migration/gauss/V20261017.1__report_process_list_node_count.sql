-- Precondition: nodes_json contains canonical JSON arrays written by ReportProcessService.
-- Invalid historical JSON fails this migration visibly rather than silently returning zero.
ALTER TABLE report_process ADD COLUMN IF NOT EXISTS node_count INTEGER;
UPDATE report_process SET node_count = CASE
    WHEN nodes_json IS NULL OR TRIM(nodes_json) = '' THEN 0
    ELSE json_array_length(CAST(nodes_json AS json)) END
WHERE node_count IS NULL;
ALTER TABLE report_process ALTER COLUMN node_count SET DEFAULT 0;
ALTER TABLE report_process ALTER COLUMN node_count SET NOT NULL;
COMMENT ON COLUMN report_process.node_count IS 'Canonical node array length for lightweight definition lists';
