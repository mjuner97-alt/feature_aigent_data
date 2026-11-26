DO $$
BEGIN
    ALTER TABLE digital_qa_ppt_keyword_config
        ADD COLUMN notification_enabled BOOLEAN NOT NULL DEFAULT TRUE;
EXCEPTION WHEN duplicate_column THEN NULL;
END;
$$;

COMMENT ON COLUMN digital_qa_ppt_keyword_config.notification_enabled IS '例会类型通知总开关，控制该类型的自动和手动邮件';
COMMENT ON COLUMN digital_qa_ppt_keyword_config.reminder_cutoff IS '旧版每日通知截止时间，北京时间 HH:mm，未设置新时段及星期时段时生效';
COMMENT ON COLUMN digital_qa_ppt_keyword_config.reminder_weekly_start_minute IS '旧版星期通知时段的开始时间，北京时间周一 00:00 起算，未设置新时段时生效';
COMMENT ON COLUMN digital_qa_ppt_keyword_config.reminder_weekly_end_minute IS '旧版星期通知时段的结束时间，跨到下一周时以当周结束为截止，未设置新时段时生效';

CREATE TABLE IF NOT EXISTS digital_qa_ppt_reminder_window (
    config_id BIGINT NOT NULL REFERENCES digital_qa_ppt_keyword_config(id) ON DELETE CASCADE,
    daily BOOLEAN NOT NULL,
    start_minute INTEGER NOT NULL,
    end_minute INTEGER NOT NULL,
    PRIMARY KEY (config_id, daily, start_minute, end_minute),
    CHECK (start_minute >= 0 AND start_minute < CASE WHEN daily THEN 1440 ELSE 10080 END),
    CHECK (end_minute >= 0 AND end_minute < CASE WHEN daily THEN 1440 ELSE 10080 END),
    CHECK (start_minute <> end_minute)
);

COMMENT ON TABLE digital_qa_ppt_reminder_window IS '例会类型的自动和手动通知发送时段，仅对报告上传当周生效';
COMMENT ON COLUMN digital_qa_ppt_reminder_window.config_id IS '所属例会类型配置 ID';
COMMENT ON COLUMN digital_qa_ppt_reminder_window.daily IS '是否按上传当周每天的相同时段配置，否表示按上传当周星期配置';
COMMENT ON COLUMN digital_qa_ppt_reminder_window.start_minute IS '北京时间起算的开始分钟，含该分钟，每日起点为零点，每周起点为周一零点';
COMMENT ON COLUMN digital_qa_ppt_reminder_window.end_minute IS '北京时间起算的结束分钟，不含该分钟，跨到下一周的时段以当周结束为截止';
