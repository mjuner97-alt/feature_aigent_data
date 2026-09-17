-- 长任务流程增加公开触发状态:
-- false(默认)=仅创建人自己的对话可命中触发;true=所有人的对话都可命中(由开发人员开通,普通更新接口不可修改)。
-- 公开流程被创建人修改保存后,后端须将本字段置回 false,需重新联系开发人员开通。
alter table skill_flow
    add column chat_public boolean default false not null;

comment on column skill_flow.chat_public is '是否公开触发:开启后所有用户的聊天均可命中该流程;仅开发人员可开通,创建人修改流程后自动退出公开';
