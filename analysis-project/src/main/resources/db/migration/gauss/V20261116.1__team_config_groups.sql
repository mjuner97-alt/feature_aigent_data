-- 团队配置及人员名单统一保存在原通知配置表中。
ALTER TABLE long_task_report_notify_person ADD COLUMN record_kind VARCHAR(8) NOT NULL DEFAULT 'PERSON';
ALTER TABLE long_task_report_notify_person ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE long_task_report_notify_person ALTER COLUMN user_id DROP NOT NULL;
ALTER TABLE long_task_report_notify_person ALTER COLUMN user_name DROP NOT NULL;
ALTER TABLE long_task_report_notify_person ALTER COLUMN recipient_type DROP NOT NULL;

-- 团队记录无人员身份且不参与通知，人员记录继续要求姓名、账号和通知类型。
ALTER TABLE long_task_report_notify_person ADD CONSTRAINT ck_long_task_notify_record CHECK (
    (record_kind = 'TEAM' AND user_id IS NULL AND user_name IS NULL AND recipient_type IS NULL AND enabled = FALSE)
    OR
    (record_kind = 'PERSON' AND user_id IS NOT NULL AND user_name IS NOT NULL AND recipient_type IS NOT NULL)
);
CREATE UNIQUE INDEX uk_long_task_notify_team
    ON long_task_report_notify_person(dev_dept, stat_group) WHERE record_kind = 'TEAM';

COMMENT ON TABLE long_task_report_notify_person IS '团队默认通知配置；TEAM 保存团队及版本，PERSON 保存有序收件人和抄送人';
COMMENT ON COLUMN long_task_report_notify_person.record_kind IS '记录类型：TEAM 为团队配置，PERSON 为通知人员；历史人员默认 PERSON';
COMMENT ON COLUMN long_task_report_notify_person.version IS '团队配置版本；用于检测并发编辑，人员记录使用默认值';

-- 回填团队配置记录，保留原有人员主键、身份和名单设置。
INSERT INTO long_task_report_notify_person (dev_dept, stat_group, record_kind, enabled)
SELECT DISTINCT dev_dept, stat_group, 'TEAM', FALSE
FROM long_task_report_notify_person WHERE record_kind = 'PERSON';
