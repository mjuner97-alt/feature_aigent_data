DO $$
BEGIN
    BEGIN
        ALTER TABLE digital_qa_ppt_keyword_config
            ADD COLUMN reminder_weekly_start_minute INTEGER
                CHECK (reminder_weekly_start_minute BETWEEN 0 AND 10079);
    EXCEPTION WHEN duplicate_column THEN NULL;
    END;

    BEGIN
        ALTER TABLE digital_qa_ppt_keyword_config
            ADD COLUMN reminder_weekly_end_minute INTEGER
                CHECK (reminder_weekly_end_minute BETWEEN 0 AND 10079);
    EXCEPTION WHEN duplicate_column THEN NULL;
    END;
END;
$$;

COMMENT ON COLUMN digital_qa_ppt_keyword_config.reminder_weekly_start_minute IS '每周提醒开始时间，北京时间周一 00:00 起算的分钟数，空值表示每日模式';
COMMENT ON COLUMN digital_qa_ppt_keyword_config.reminder_weekly_end_minute IS '每周提醒结束时间，北京时间周一 00:00 起算的分钟数，小于开始时跨周';
