-- 在 V20261106.1 之后执行：人员来源表仅维护部门、团队及默认通知名单。
-- 关键词和联系人属于保存后的任务配置，不再从人员来源表读取。
-- 已保存的 long_task_report_param 参数行及历史执行快照保持不变。
ALTER TABLE long_task_report_notify_person DROP COLUMN report_keyword;
ALTER TABLE long_task_report_notify_person DROP COLUMN notify_contact_name;
ALTER TABLE long_task_report_notify_person DROP COLUMN notify_contact_phone;

COMMENT ON TABLE long_task_report_notify_person IS '长任务报告默认通知人员来源；按部门、团队维护人员及主送抄送，首次添加对象时回显名单';
COMMENT ON COLUMN long_task_report_param.notify_contact_name IS '本报告对象独立维护的联系人姓名，创建执行时冻结到该对象的通知快照';
COMMENT ON COLUMN long_task_report_param.notify_contact_phone IS '本报告对象独立维护的联系电话，创建执行时冻结到该对象的通知快照';
