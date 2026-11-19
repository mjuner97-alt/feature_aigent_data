ALTER TABLE digital_qa_ppt_slide ALTER COLUMN assigned_owner TYPE VARCHAR(2048);
COMMENT ON COLUMN digital_qa_ppt_slide.assigned_owner IS '当前材料版本的板块联系人认证号快照，多个认证号以英文逗号分隔';
