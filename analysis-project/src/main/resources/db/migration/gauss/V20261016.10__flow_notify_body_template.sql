-- 长任务通知邮件正文模板：文字部分在编辑页配置，后端只负责拼接报告链接。
-- 模板中的 {链接} 占位符发送时替换为 <a href="报告地址">任务名(年月日)</a>；
-- 未写占位符时链接独立成段追加在正文之后；模板为空时使用系统默认文案。
ALTER TABLE skill_flow ADD COLUMN IF NOT EXISTS notify_body_template TEXT;
COMMENT ON COLUMN skill_flow.notify_body_template IS '长任务通知邮件正文模板；{链接} 占位符发送时替换为报告链接(显示为 任务名(年月日))，未写占位符时链接追加在正文末尾，未配置时使用系统默认文案';

-- 执行创建时保存正文模板快照，首次通知与重发均使用快照，不受后续流程编辑影响。
-- 历史执行的快照为空，继续使用系统默认文案。
ALTER TABLE skill_flow_execution ADD COLUMN IF NOT EXISTS notify_body_template_snapshot TEXT;
COMMENT ON COLUMN skill_flow_execution.notify_body_template_snapshot IS '执行创建时的通知邮件正文模板快照；{链接} 占位符发送时替换为报告链接，为空时使用系统默认文案';
