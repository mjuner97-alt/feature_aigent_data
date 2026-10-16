-- 长任务通知中的联系信息：用于邮件正文展示，方便收件人咨询，不作为邮件收件人或短信接收号码。
-- 联系人和手机号成对配置；两项均为空时，发送侧继续使用运行配置表 notification_contact_html。
-- 业务层去除首尾空格后，仅校验手机号长度为 11，不增加号段等格式校验。
ALTER TABLE skill_flow ADD COLUMN IF NOT EXISTS notify_contact_name TEXT;
ALTER TABLE skill_flow ADD COLUMN IF NOT EXISTS notify_contact_phone TEXT;
COMMENT ON COLUMN skill_flow.notify_contact_name IS '长任务通知邮件展示的自定义联系人姓名；与手机号成对填写，两项为空时使用系统默认联系信息';
COMMENT ON COLUMN skill_flow.notify_contact_phone IS '长任务通知邮件展示的联系人手机号，仅校验去除首尾空格后长度为 11；不用于发送短信';

-- 执行创建时保存自定义联系方式，首次通知与重发均使用快照，不受后续流程编辑影响。
-- 历史执行的快照为空，继续使用发送时配置表中的默认联系人 HTML。
ALTER TABLE skill_flow_execution ADD COLUMN IF NOT EXISTS notify_contact_name_snapshot TEXT;
ALTER TABLE skill_flow_execution ADD COLUMN IF NOT EXISTS notify_contact_phone_snapshot TEXT;
COMMENT ON COLUMN skill_flow_execution.notify_contact_name_snapshot IS '执行创建时的自定义通知联系人姓名快照；与手机号快照均为空时使用系统默认联系信息';
COMMENT ON COLUMN skill_flow_execution.notify_contact_phone_snapshot IS '执行创建时的自定义通知联系人手机号快照；用于首次通知及重发时在邮件正文展示';
