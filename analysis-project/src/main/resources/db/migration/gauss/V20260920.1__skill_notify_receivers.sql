-- 通知收件人名单:定时任务(skill_job)与长任务流程(skill_flow)支持配置多个收件人,
-- 未配置时维持原行为(发给创建人/触发人)。存储格式为逗号分隔的 userId(统一认证号),
-- 值域与 skill_job.created_by / skill_flow_execution.trigger_user_id 一致,发送时一次性批量透传邮件 toUserList。
ALTER TABLE skill_job ADD COLUMN IF NOT EXISTS notify_receivers VARCHAR(1024);

ALTER TABLE skill_flow ADD COLUMN IF NOT EXISTS notify_receivers VARCHAR(1024);
ALTER TABLE skill_flow ADD COLUMN IF NOT EXISTS notify_receiver_triggers VARCHAR(128);

-- 执行记录快照名单与触发类型范围,保证执行期间修改配置不影响已触发的执行
ALTER TABLE skill_flow_execution ADD COLUMN IF NOT EXISTS notify_receivers_snapshot VARCHAR(1024);
ALTER TABLE skill_flow_execution ADD COLUMN IF NOT EXISTS notify_receiver_triggers_snapshot VARCHAR(128);

comment on column skill_job.notify_receivers is '完成通知收件人 userId 列表(逗号分隔,人员表校验);空=发给创建人';
comment on column skill_flow.notify_receivers is '完成通知收件人 userId 列表(逗号分隔,人员表校验);空=发给触发人';
comment on column skill_flow.notify_receiver_triggers is '哪些触发类型发收件人名单(逗号分隔,取值 CHAT/MANUAL/AUTO_METRIC);空=仅 AUTO_METRIC';
comment on column skill_flow_execution.notify_receivers_snapshot is '完成通知收件人名单快照(逗号分隔)';
comment on column skill_flow_execution.notify_receiver_triggers_snapshot is '发名单的触发类型范围快照(逗号分隔);空=仅 AUTO_METRIC';

-- 通知记录表的收件人列原按单收件人设计(varchar 256/512),扩容以容纳逗号拼接的多人名单
ALTER TABLE skill_flow_notification ALTER COLUMN recipient TYPE varchar(1024);
ALTER TABLE skill_job_notification ALTER COLUMN recipient_summary TYPE varchar(1024);
