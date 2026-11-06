-- dev_dept 已是部门字段，无需引入重复的部门名称字段。
COMMENT ON COLUMN team_report_notify_person.dev_dept IS '部门完整名称；部门候选直接由本表已启用记录去重取得';
COMMENT ON COLUMN team_report_notify_person.stat_group IS '团队完整名称；团队候选按本表部门内去重取得；空字符串用于部门报告名单';
COMMENT ON TABLE team_report_notify_person IS '报告首次配置来源：部门、团队、默认关键词及联系人、收件人和抄送人；保存后的任务使用独立参数池';
ALTER TABLE team_report_notify_person ADD COLUMN report_keyword VARCHAR(500);
ALTER TABLE team_report_notify_person ADD COLUMN notify_contact_name VARCHAR(200);
ALTER TABLE team_report_notify_person ADD COLUMN notify_contact_phone VARCHAR(32);
COMMENT ON COLUMN team_report_notify_person.report_keyword IS '本部门或团队初始关键词；同一对象非空值须一致，空值使用原有默认命名';
COMMENT ON COLUMN team_report_notify_person.notify_contact_name IS '本部门或团队初始联系人姓名；同一对象非空值须一致，不从其他对象继承';
COMMENT ON COLUMN team_report_notify_person.notify_contact_phone IS '本部门或团队初始联系人电话；同一对象非空值须一致，不从其他对象继承';
-- 保留此前维护的默认值，这些字典不再提供组织候选项。
UPDATE team_report_notify_person p SET report_keyword=old_config.report_keyword,notify_contact_name=old_config.notify_contact_name,notify_contact_phone=old_config.notify_contact_phone
 FROM (SELECT d.name AS dev_dept,t.name AS stat_group,t.report_keyword,t.notify_contact_name,t.notify_contact_phone
       FROM team_report_team t JOIN team_report_department d ON t.department_id=d.id) old_config
 WHERE p.dev_dept=old_config.dev_dept AND p.stat_group=old_config.stat_group;
UPDATE team_report_notify_person p SET report_keyword=d.report_keyword,notify_contact_name=d.notify_contact_name,notify_contact_phone=d.notify_contact_phone
 FROM team_report_department d WHERE p.dev_dept=d.name AND p.stat_group='';
CREATE INDEX idx_team_notify_org ON team_report_notify_person(enabled,dev_dept,stat_group);
