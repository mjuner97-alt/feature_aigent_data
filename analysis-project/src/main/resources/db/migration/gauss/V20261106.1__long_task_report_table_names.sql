-- 在 V20261105.5 之后执行，统一近期新增报告表的 long_task_report_ 前缀。
-- 仅重命名表，保留原有数据、外键、索引及自增序列；历史迁移脚本保持不变。
-- 已部署环境须先执行本脚本，再启用使用新表名的应用版本。
ALTER TABLE team_report_department RENAME TO long_task_report_department;
ALTER TABLE team_report_team RENAME TO long_task_report_team;
ALTER TABLE team_report_notify_person RENAME TO long_task_report_notify_person;
ALTER TABLE skill_flow_report_param RENAME TO long_task_report_param;

COMMENT ON TABLE long_task_report_department IS '长任务报告部门字典；通过名称关联稳定标识，候选来自已启用的通知来源记录';
COMMENT ON TABLE long_task_report_team IS '长任务报告团队字典；保存所属部门、稳定标识和排序，候选来自已启用的通知来源记录';
COMMENT ON COLUMN long_task_report_team.department_id IS '所属部门标识；关联 long_task_report_department.id';
COMMENT ON TABLE long_task_report_notify_person IS '长任务报告首次配置来源；保存部门、团队、默认关键词、联系人及通知人员，任务保存后使用独立参数池';
COMMENT ON TABLE long_task_report_param IS '长任务报告参数池；一行代表一份报告的执行配置，部门报告 team_id 为空字符串';
