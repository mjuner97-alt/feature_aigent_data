ALTER TABLE digital_qa_ppt_finding ALTER COLUMN anomaly_keyword TYPE TEXT;
COMMENT ON COLUMN digital_qa_ppt_finding.anomaly_keyword IS '例会材料关注事项命中的异常关键词，可记录多个关键词';
