-- 历史记录来源环境未知，保留为空；仅在全部环境查询中展示。
ALTER TABLE digital_qa_resource_change_event
    ADD COLUMN IF NOT EXISTS environment LowCardinality(String) DEFAULT '';
