-- 仅在首次选择或新增对象时读取默认值，重新加载已保存记录时不读取。
ALTER TABLE team_report_department ADD COLUMN report_keyword VARCHAR(500);
ALTER TABLE team_report_department ADD COLUMN notify_contact_name VARCHAR(200);
ALTER TABLE team_report_department ADD COLUMN notify_contact_phone VARCHAR(32);
COMMENT ON COLUMN team_report_department.report_keyword IS '部门报告初始关键词；为空时使用部门名加部门报告';
COMMENT ON COLUMN team_report_department.notify_contact_name IS '部门报告初始联系人姓名；团队报告不继承此值';
COMMENT ON COLUMN team_report_department.notify_contact_phone IS '部门报告初始联系人电话；团队报告不继承此值';

ALTER TABLE team_report_team ADD COLUMN report_keyword VARCHAR(500);
ALTER TABLE team_report_team ADD COLUMN notify_contact_name VARCHAR(200);
ALTER TABLE team_report_team ADD COLUMN notify_contact_phone VARCHAR(32);
COMMENT ON COLUMN team_report_team.report_keyword IS '本团队报告初始关键词；为空时使用团队名加团队报告';
COMMENT ON COLUMN team_report_team.notify_contact_name IS '本团队报告初始联系人姓名；不继承部门或其他团队';
COMMENT ON COLUMN team_report_team.notify_contact_phone IS '本团队报告初始联系人电话；不继承部门或其他团队';

ALTER TABLE skill_flow_report_param ADD COLUMN notify_contact_name VARCHAR(200);
ALTER TABLE skill_flow_report_param ADD COLUMN notify_contact_phone VARCHAR(32);
COMMENT ON COLUMN skill_flow_report_param.notify_contact_name IS '本参数池对象保存的联系人姓名；执行时沿用既有通知联系人快照字段';
COMMENT ON COLUMN skill_flow_report_param.notify_contact_phone IS '本参数池对象保存的联系人电话；保存后不随默认配置变化';
-- 保留历史已保存配置实际使用的联系人。
UPDATE skill_flow_report_param p SET notify_contact_name=f.notify_contact_name,notify_contact_phone=f.notify_contact_phone
 FROM skill_flow f WHERE p.flow_id=f.id;
