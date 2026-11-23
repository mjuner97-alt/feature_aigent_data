ALTER TABLE digital_qa_ppt_keyword_config
    ADD COLUMN IF NOT EXISTS reminder_enabled BOOLEAN,
    ADD COLUMN IF NOT EXISTS reminder_cutoff VARCHAR(5),
    ADD COLUMN IF NOT EXISTS reminder_interval_minutes INTEGER CHECK (reminder_interval_minutes > 0);

COMMENT ON COLUMN digital_qa_ppt_keyword_config.reminder_enabled IS '材料类型是否开启自动逾期提醒，空值沿用默认配置';
COMMENT ON COLUMN digital_qa_ppt_keyword_config.reminder_cutoff IS '材料类型每日提醒截止时间，北京时间 HH:mm，空值沿用默认配置';
COMMENT ON COLUMN digital_qa_ppt_keyword_config.reminder_interval_minutes IS '材料类型逾期提醒间隔，单位分钟，空值沿用默认配置';
