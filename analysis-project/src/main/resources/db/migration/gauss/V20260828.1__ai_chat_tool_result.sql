-- ============================================================================
-- /ai/chat 工具结果引用与会话历史 GaussDB 迁移
-- ----------------------------------------------------------------------------
-- 背景: /ai/chat 最终回答用 {{TOOL_RESULT:tr_xxx}} 标记引用 script_exec 产出的
-- 图表块(```echarts / ```html)。图表原文不回流 LLM,登记到结果池;answer 落库时
-- 保留标记,回放时按 refId 懒拉取。
--
-- ai_chat_tool_result: 工具结果池持久层(内存 map 的重启兜底 + 回放数据源)
-- ai_chat_message:     会话消息历史。此前 MainAgentMapper 的 answer 落库 SQL
--                      从未实现(XML 为空,运行时 BindingException 被 cleanup
--                      吞掉),前端刷新后无任何恢复通路,本表补齐这一环。
-- 目标数据库: openGauss (PostgreSQL 兼容)
-- ============================================================================

CREATE TABLE IF NOT EXISTS ai_chat_tool_result (
    ref_id          varchar(32)             not null
        primary key,
    conversation_id varchar(128)            not null,
    tool_call_id    varchar(64),
    tool_name       varchar(128),
    content         text                    not null,
    created_at      timestamp default now() not null
)
    with (orientation = row, compression = no);

comment on table ai_chat_tool_result is '/ai/chat 工具结果引用池:一行=一个图表块,refId 全局唯一且不复用';

comment on column ai_chat_tool_result.ref_id is '结果引用标志,格式 tr_+UUID截断12位,每次工具调用新生成';

comment on column ai_chat_tool_result.conversation_id is '产生该结果的会话 id';

comment on column ai_chat_tool_result.tool_call_id is '工具调用 id(框架 toolCallId),可空';

comment on column ai_chat_tool_result.tool_name is '工具名(当前仅 script_exec)';

comment on column ai_chat_tool_result.content is '结果原文(图表围栏块,```echarts/```html)';

comment on column ai_chat_tool_result.created_at is '创建时间';

alter table ai_chat_tool_result
    owner to readwriter;

create index idx_ai_chat_tool_result_conversation
    on ai_chat_tool_result (conversation_id, created_at);

CREATE TABLE IF NOT EXISTS ai_chat_message (
    id              bigserial
        primary key,
    conversation_id varchar(128)            not null,
    user_id         varchar(64),
    role            varchar(16)             not null,
    content         text                    not null,
    think           text,
    created_at      timestamp default now() not null
)
    with (orientation = row, compression = no);

comment on table ai_chat_message is '/ai/chat 会话消息历史:一轮问答两行(user+assistant),assistant 的 content 保留 TOOL_RESULT 标记';

comment on column ai_chat_message.conversation_id is '会话 id';

comment on column ai_chat_message.user_id is '用户 id,可空';

comment on column ai_chat_message.role is '消息角色: user / assistant';

comment on column ai_chat_message.content is '消息正文;assistant 正文中的 {{TOOL_RESULT:tr_xxx}} 由前端按 refId 懒拉渲染';

comment on column ai_chat_message.think is '思考过程,仅 assistant 行有,可空';

comment on column ai_chat_message.created_at is '创建时间';

alter table ai_chat_message
    owner to readwriter;

create index idx_ai_chat_message_conversation
    on ai_chat_message (conversation_id, id);
