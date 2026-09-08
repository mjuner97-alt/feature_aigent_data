-- ============================================================================
-- 多 Skill 长任务 V1:流程定义、触发关键词、指标就绪事实与执行快照
--
-- 背景:单个 Skill Job 只能跑一个技能;多个 Skill 组成的长任务需要
--       指标门闩(等数据就绪) -> DAG 并行执行 -> 最终汇总 的完整链路,
--       以及独立的触发关键词匹配与一次性完成通知。
--
-- 本迁移:
--   1. 流程定义三张表:skill_flow(流程头)/skill_flow_node(节点+依赖 DAG)
--      /skill_flow_node_metric(节点依赖指标)
--   2. 触发关键词表 skill_flow_trigger(CONTAINS 匹配,normalized_keyword 全局唯一)
--   3. 指标每日就绪事实表 skill_metric_readiness(READY/EXPIRED)
--   4. 执行快照四张表:skill_flow_execution(父任务)/skill_flow_node_execution
--      (节点快照)/skill_flow_node_attempt(尝试审计)/skill_flow_notification(通知)
-- ============================================================================

CREATE TABLE IF NOT EXISTS skill_flow (
    id BIGSERIAL PRIMARY KEY,
    code VARCHAR(128) NOT NULL,
    name VARCHAR(128) NOT NULL,
    description TEXT NULL,
    summary_question_template TEXT NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    max_parallelism INTEGER NOT NULL DEFAULT 4 CHECK (max_parallelism > 0),
    notify_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_by VARCHAR(64) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    updated_at TIMESTAMP NOT NULL DEFAULT now(),
    deleted_at TIMESTAMP NULL,
    UNIQUE (code)
);

COMMENT ON TABLE skill_flow IS '长任务流程定义表(流程头),一行=一个多 Skill 流程;节点在 skill_flow_node';
COMMENT ON COLUMN skill_flow.id IS '主键';
COMMENT ON COLUMN skill_flow.code IS '流程编码,全局唯一';
COMMENT ON COLUMN skill_flow.name IS '流程名称';
COMMENT ON COLUMN skill_flow.description IS '流程描述,可空';
COMMENT ON COLUMN skill_flow.summary_question_template IS '最终汇总问题模板,全部 Skill 终态后渲染执行';
COMMENT ON COLUMN skill_flow.enabled IS '是否启用,启用后触发关键词才会匹配';
COMMENT ON COLUMN skill_flow.max_parallelism IS '流程内 Skill 节点最大并发数,>0';
COMMENT ON COLUMN skill_flow.notify_enabled IS '流程完成是否发送通知';
COMMENT ON COLUMN skill_flow.created_by IS '创建人';
COMMENT ON COLUMN skill_flow.created_at IS '创建时间';
COMMENT ON COLUMN skill_flow.updated_at IS '更新时间';
COMMENT ON COLUMN skill_flow.deleted_at IS '软删除时间,非空即已删除';

CREATE INDEX IF NOT EXISTS idx_skill_flow_enabled ON skill_flow (enabled, deleted_at);

CREATE TABLE IF NOT EXISTS skill_flow_node (
    id BIGSERIAL PRIMARY KEY,
    flow_id BIGINT NOT NULL REFERENCES skill_flow(id),
    node_key VARCHAR(128) NOT NULL,
    skill_id BIGINT NOT NULL REFERENCES skill_manage(id),
    question_template TEXT NOT NULL,
    depends_on_json TEXT NOT NULL DEFAULT '[]',
    required BOOLEAN NOT NULL DEFAULT TRUE,
    max_attempts INTEGER NOT NULL DEFAULT 4 CHECK (max_attempts > 0),
    sort_order INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    updated_at TIMESTAMP NOT NULL DEFAULT now(),
    UNIQUE (flow_id, node_key)
);

COMMENT ON TABLE skill_flow_node IS '长任务流程节点定义表,一行=一个 Skill 节点;依赖关系构成 DAG(不支持循环/条件分支)';
COMMENT ON COLUMN skill_flow_node.id IS '主键';
COMMENT ON COLUMN skill_flow_node.flow_id IS '所属流程 id,引用 skill_flow(id)';
COMMENT ON COLUMN skill_flow_node.node_key IS '节点 key,流程内唯一;depends_on_json 引用它';
COMMENT ON COLUMN skill_flow_node.skill_id IS '节点绑定的 Skill,引用 skill_manage(id)';
COMMENT ON COLUMN skill_flow_node.question_template IS '该节点的问题模板,渲染后作为 Skill 的输入问题';
COMMENT ON COLUMN skill_flow_node.depends_on_json IS '前置节点 key 数组(JSON),空数组=门闩打开后可直接并行执行';
COMMENT ON COLUMN skill_flow_node.required IS '是否必需节点:必需失败则任务 FAILED,可选失败只影响 PARTIAL_SUCCESS';
COMMENT ON COLUMN skill_flow_node.max_attempts IS '节点最大尝试次数(含首次),>0';
COMMENT ON COLUMN skill_flow_node.sort_order IS '展示排序号';
COMMENT ON COLUMN skill_flow_node.created_at IS '创建时间';
COMMENT ON COLUMN skill_flow_node.updated_at IS '更新时间';

CREATE INDEX IF NOT EXISTS idx_skill_flow_node_flow_order ON skill_flow_node (flow_id, sort_order);
CREATE INDEX IF NOT EXISTS idx_skill_flow_node_skill ON skill_flow_node (skill_id);

CREATE TABLE IF NOT EXISTS skill_flow_node_metric (
    id BIGSERIAL PRIMARY KEY,
    flow_node_id BIGINT NOT NULL REFERENCES skill_flow_node(id),
    metric_id BIGINT NOT NULL REFERENCES skill_dependency_metric(id),
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    UNIQUE (flow_node_id, metric_id)
);

COMMENT ON TABLE skill_flow_node_metric IS '节点依赖指标关系表:节点执行前其全部依赖指标须 READY(指标门闩)';
COMMENT ON COLUMN skill_flow_node_metric.id IS '主键';
COMMENT ON COLUMN skill_flow_node_metric.flow_node_id IS '节点 id,引用 skill_flow_node(id)';
COMMENT ON COLUMN skill_flow_node_metric.metric_id IS '指标 id,引用 skill_dependency_metric(id)';
COMMENT ON COLUMN skill_flow_node_metric.created_at IS '创建时间';

CREATE INDEX IF NOT EXISTS idx_skill_flow_node_metric_metric ON skill_flow_node_metric (metric_id, flow_node_id);

CREATE TABLE IF NOT EXISTS skill_flow_trigger (
    id BIGSERIAL PRIMARY KEY,
    flow_id BIGINT NOT NULL REFERENCES skill_flow(id),
    keyword VARCHAR(256) NOT NULL,
    normalized_keyword VARCHAR(256) NOT NULL,
    priority INTEGER NOT NULL DEFAULT 0,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_by VARCHAR(64) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    updated_at TIMESTAMP NOT NULL DEFAULT now(),
    UNIQUE (normalized_keyword)
);

COMMENT ON TABLE skill_flow_trigger IS '触发关键词表:消息 CONTAINS 命中即创建长任务(V1 不开放正则);同一消息命中多个流程时取 priority 最高,同优先级按 id 升序';
COMMENT ON COLUMN skill_flow_trigger.id IS '主键,同优先级冲突时的决胜键(升序)';
COMMENT ON COLUMN skill_flow_trigger.flow_id IS '命中后创建的流程 id,引用 skill_flow(id)';
COMMENT ON COLUMN skill_flow_trigger.keyword IS '触发关键词原文';
COMMENT ON COLUMN skill_flow_trigger.normalized_keyword IS '规范化关键词,全局唯一:同一关键词不允许关联多个流程';
COMMENT ON COLUMN skill_flow_trigger.priority IS '优先级,越大越先命中;不同关键词命中不同流程时取最高者';
COMMENT ON COLUMN skill_flow_trigger.enabled IS '是否启用';
COMMENT ON COLUMN skill_flow_trigger.created_by IS '创建人';
COMMENT ON COLUMN skill_flow_trigger.created_at IS '创建时间';
COMMENT ON COLUMN skill_flow_trigger.updated_at IS '更新时间';

CREATE INDEX IF NOT EXISTS idx_skill_flow_trigger_enabled_priority ON skill_flow_trigger (enabled, priority DESC, id ASC);
CREATE INDEX IF NOT EXISTS idx_skill_flow_trigger_flow ON skill_flow_trigger (flow_id);

CREATE TABLE IF NOT EXISTS skill_metric_readiness (
    id BIGSERIAL PRIMARY KEY,
    metric_id BIGINT NOT NULL REFERENCES skill_dependency_metric(id),
    metric_code VARCHAR(128) NOT NULL,
    data_date DATE NOT NULL,
    status VARCHAR(16) NOT NULL,
    ready_at TIMESTAMP NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    metadata_json TEXT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    updated_at TIMESTAMP NOT NULL DEFAULT now(),
    UNIQUE (metric_id, data_date),
    CHECK (status IN ('READY', 'EXPIRED'))
);

COMMENT ON TABLE skill_metric_readiness IS '指标每日就绪事实表:一行=某指标某数据日的是否就绪;只记事实,不表示 Skill 已执行';
COMMENT ON COLUMN skill_metric_readiness.id IS '主键';
COMMENT ON COLUMN skill_metric_readiness.metric_id IS '指标 id,引用 skill_dependency_metric(id);与 data_date 联合唯一';
COMMENT ON COLUMN skill_metric_readiness.metric_code IS '指标编码(冗余,便于按 code 查询)';
COMMENT ON COLUMN skill_metric_readiness.data_date IS '数据日期';
COMMENT ON COLUMN skill_metric_readiness.status IS '就绪状态:READY(就绪)/EXPIRED(已过期)';
COMMENT ON COLUMN skill_metric_readiness.ready_at IS '就绪时间';
COMMENT ON COLUMN skill_metric_readiness.expires_at IS '就绪有效期截止时间,过期后按 EXPIRED 处理';
COMMENT ON COLUMN skill_metric_readiness.metadata_json IS '就绪元数据(JSON),可空';
COMMENT ON COLUMN skill_metric_readiness.created_at IS '创建时间';
COMMENT ON COLUMN skill_metric_readiness.updated_at IS '更新时间';

CREATE INDEX IF NOT EXISTS idx_skill_metric_readiness_code_date ON skill_metric_readiness (metric_code, data_date, status);
CREATE INDEX IF NOT EXISTS idx_skill_metric_readiness_expiry ON skill_metric_readiness (expires_at, status);

CREATE TABLE IF NOT EXISTS skill_flow_execution (
    id BIGSERIAL PRIMARY KEY,
    flow_id BIGINT NOT NULL REFERENCES skill_flow(id),
    flow_code VARCHAR(128) NOT NULL,
    flow_name VARCHAR(128) NOT NULL,
    summary_question_template_snapshot TEXT NOT NULL,
    max_parallelism_snapshot INTEGER NOT NULL DEFAULT 4 CHECK (max_parallelism_snapshot > 0),
    notify_enabled_snapshot BOOLEAN NOT NULL DEFAULT TRUE,
    trigger_type VARCHAR(32) NOT NULL,
    trigger_user_id VARCHAR(64) NOT NULL,
    conversation_id VARCHAR(128) NOT NULL,
    original_question TEXT NOT NULL,
    data_date DATE NOT NULL,
    status VARCHAR(32) NOT NULL,
    active_guard_key VARCHAR(512) NULL,
    required_metric_count INTEGER NOT NULL DEFAULT 0 CHECK (required_metric_count >= 0),
    ready_metric_count INTEGER NOT NULL DEFAULT 0 CHECK (ready_metric_count >= 0),
    missing_metrics_json TEXT NOT NULL DEFAULT '[]',
    summary_json TEXT NULL,
    report_path VARCHAR(1024) NULL,
    cancel_requested_at TIMESTAMP NULL,
    started_at TIMESTAMP NULL,
    completed_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    updated_at TIMESTAMP NOT NULL DEFAULT now(),
    UNIQUE (active_guard_key)
);

COMMENT ON TABLE skill_flow_execution IS '长任务父执行记录:一行=一次长任务;创建即持久化(不等指标),终态后同 guard key 可再建新任务';
COMMENT ON COLUMN skill_flow_execution.id IS '主键,即返回给前端的 taskId';
COMMENT ON COLUMN skill_flow_execution.flow_id IS '流程 id,引用 skill_flow(id)';
COMMENT ON COLUMN skill_flow_execution.flow_code IS '流程编码快照(创建时刻的值)';
COMMENT ON COLUMN skill_flow_execution.flow_name IS '流程名称快照';
COMMENT ON COLUMN skill_flow_execution.summary_question_template_snapshot IS '汇总问题模板快照:流程事后修改不影响运行中任务';
COMMENT ON COLUMN skill_flow_execution.max_parallelism_snapshot IS '最大并发数快照,>0';
COMMENT ON COLUMN skill_flow_execution.notify_enabled_snapshot IS '是否通知快照';
COMMENT ON COLUMN skill_flow_execution.trigger_type IS '触发类型,如关键词触发/手动触发';
COMMENT ON COLUMN skill_flow_execution.trigger_user_id IS '触发人用户 id';
COMMENT ON COLUMN skill_flow_execution.conversation_id IS '触发消息所在会话 id';
COMMENT ON COLUMN skill_flow_execution.original_question IS '触发时的用户原始问题';
COMMENT ON COLUMN skill_flow_execution.data_date IS '本次任务的数据日期(指标就绪按该日判定)';
COMMENT ON COLUMN skill_flow_execution.status IS '状态。非终态:WAITING_METRICS(等指标)/QUEUED(等 worker)/RUNNING(执行中)/CANCEL_REQUESTED(用户要求直接回答)/SUMMARIZING(汇总中);终态:SUCCESS(全部成功)/PARTIAL_SUCCESS(必需成功+可选失败或阻塞)/FAILED(必需失败/阻塞/当天未集齐指标)/CANCELLED(用户取消)';
COMMENT ON COLUMN skill_flow_execution.active_guard_key IS '活动任务防重键:userId+conversationId+flowId+dataDate 规范化生成,唯一约束;进入终态时清空(允许重建),并发创建冲突时查现有 execution 返回而非先查再插';
COMMENT ON COLUMN skill_flow_execution.required_metric_count IS '任务依赖的指标总数';
COMMENT ON COLUMN skill_flow_execution.ready_metric_count IS '已 READY 的指标数';
COMMENT ON COLUMN skill_flow_execution.missing_metrics_json IS '未就绪指标清单(JSON 数组),默认 []';
COMMENT ON COLUMN skill_flow_execution.summary_json IS '最终汇总结果(JSON),可空';
COMMENT ON COLUMN skill_flow_execution.report_path IS '汇总报告文件路径,可空';
COMMENT ON COLUMN skill_flow_execution.cancel_requested_at IS '用户请求取消(直接回答)的时间,可空';
COMMENT ON COLUMN skill_flow_execution.started_at IS '开始执行时间(Skill 首次运行前),可空';
COMMENT ON COLUMN skill_flow_execution.completed_at IS '进入终态的时间,可空';
COMMENT ON COLUMN skill_flow_execution.created_at IS '创建时间';
COMMENT ON COLUMN skill_flow_execution.updated_at IS '更新时间';

CREATE INDEX IF NOT EXISTS idx_skill_flow_execution_status_date ON skill_flow_execution (status, data_date, created_at);
CREATE INDEX IF NOT EXISTS idx_skill_flow_execution_conversation_user ON skill_flow_execution (conversation_id, trigger_user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_skill_flow_execution_flow ON skill_flow_execution (flow_id, created_at DESC);

CREATE TABLE IF NOT EXISTS skill_flow_node_execution (
    id BIGSERIAL PRIMARY KEY,
    flow_execution_id BIGINT NOT NULL REFERENCES skill_flow_execution(id),
    node_key VARCHAR(128) NOT NULL,
    skill_id BIGINT NOT NULL,
    skill_name VARCHAR(128) NOT NULL,
    skill_retrieval_name VARCHAR(256) NOT NULL,
    question_template_snapshot TEXT NOT NULL,
    rendered_question TEXT NULL,
    depends_on_json TEXT NOT NULL DEFAULT '[]',
    required BOOLEAN NOT NULL DEFAULT TRUE,
    status VARCHAR(32) NOT NULL,
    attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    max_attempts INTEGER NOT NULL CHECK (max_attempts > 0),
    next_run_at TIMESTAMP NULL,
    lease_owner VARCHAR(128) NULL,
    lease_expires_at TIMESTAMP NULL,
    result_json TEXT NULL,
    artifact_path VARCHAR(1024) NULL,
    error_code VARCHAR(64) NULL,
    error_message TEXT NULL,
    started_at TIMESTAMP NULL,
    completed_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    updated_at TIMESTAMP NOT NULL DEFAULT now(),
    UNIQUE (flow_execution_id, node_key)
);

COMMENT ON TABLE skill_flow_node_execution IS '长任务节点执行快照:一行=一次任务中某 Skill 节点的执行;模板/依赖取快照,流程修改不影响运行中任务;所有节点必须进入终态';
COMMENT ON COLUMN skill_flow_node_execution.id IS '主键';
COMMENT ON COLUMN skill_flow_node_execution.flow_execution_id IS '所属父任务 id,引用 skill_flow_execution(id);与 node_key 联合唯一';
COMMENT ON COLUMN skill_flow_node_execution.node_key IS '节点 key(创建时快照)';
COMMENT ON COLUMN skill_flow_node_execution.skill_id IS 'Skill id 快照(无外键,历史任务允许引用已删 Skill)';
COMMENT ON COLUMN skill_flow_node_execution.skill_name IS 'Skill 名称快照,用于展示/审计';
COMMENT ON COLUMN skill_flow_node_execution.skill_retrieval_name IS 'Skill 检索名快照(调用执行时使用)';
COMMENT ON COLUMN skill_flow_node_execution.question_template_snapshot IS '问题模板快照';
COMMENT ON COLUMN skill_flow_node_execution.rendered_question IS '渲染后的实际问题,便于审计 Skill 实际收到的问题,可空';
COMMENT ON COLUMN skill_flow_node_execution.depends_on_json IS '前置节点 key 数组快照(JSON),默认 []';
COMMENT ON COLUMN skill_flow_node_execution.required IS '是否必需节点快照';
COMMENT ON COLUMN skill_flow_node_execution.status IS '状态。非终态:PENDING(等门闩/等前置)/QUEUED(等 worker)/RUNNING(执行中)/RETRY_WAIT(等下次重试);终态:SUCCESS/FAILED/BLOCKED(前置必需失败,不能运行)/CANCELLED';
COMMENT ON COLUMN skill_flow_node_execution.attempt_count IS '已实际执行次数,>=0;等待指标/依赖和排队不计入';
COMMENT ON COLUMN skill_flow_node_execution.max_attempts IS '最大尝试次数快照,>0';
COMMENT ON COLUMN skill_flow_node_execution.next_run_at IS '下次可运行时间(RETRY_WAIT 的退避/排队依据),可空';
COMMENT ON COLUMN skill_flow_node_execution.lease_owner IS '执行租约持有者标识(多 worker 抢占/防重复执行),可空';
COMMENT ON COLUMN skill_flow_node_execution.lease_expires_at IS '租约过期时间,过期后可被其他 worker 接管,可空';
COMMENT ON COLUMN skill_flow_node_execution.result_json IS '节点执行结果(JSON),可空';
COMMENT ON COLUMN skill_flow_node_execution.artifact_path IS '节点产物文件路径,可空';
COMMENT ON COLUMN skill_flow_node_execution.error_code IS '错误码,可空';
COMMENT ON COLUMN skill_flow_node_execution.error_message IS '错误信息,可空';
COMMENT ON COLUMN skill_flow_node_execution.started_at IS '首次开始执行时间,可空';
COMMENT ON COLUMN skill_flow_node_execution.completed_at IS '进入终态时间,可空';
COMMENT ON COLUMN skill_flow_node_execution.created_at IS '创建时间';
COMMENT ON COLUMN skill_flow_node_execution.updated_at IS '更新时间';

CREATE INDEX IF NOT EXISTS idx_skill_flow_node_execution_runnable ON skill_flow_node_execution (status, next_run_at);
CREATE INDEX IF NOT EXISTS idx_skill_flow_node_execution_lease ON skill_flow_node_execution (lease_expires_at, status);
CREATE INDEX IF NOT EXISTS idx_skill_flow_node_execution_flow ON skill_flow_node_execution (flow_execution_id, status);

CREATE TABLE IF NOT EXISTS skill_flow_node_attempt (
    id BIGSERIAL PRIMARY KEY,
    node_execution_id BIGINT NOT NULL REFERENCES skill_flow_node_execution(id),
    attempt_no INTEGER NOT NULL CHECK (attempt_no > 0),
    status VARCHAR(32) NOT NULL,
    retryable BOOLEAN NOT NULL DEFAULT FALSE,
    error_code VARCHAR(64) NULL,
    error_message TEXT NULL,
    started_at TIMESTAMP NOT NULL,
    completed_at TIMESTAMP NULL,
    duration_ms BIGINT NULL CHECK (duration_ms IS NULL OR duration_ms >= 0),
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    UNIQUE (node_execution_id, attempt_no)
);

COMMENT ON TABLE skill_flow_node_attempt IS '节点尝试审计表:一行=一次实际执行;等待指标、等待依赖和排队不创建 attempt';
COMMENT ON COLUMN skill_flow_node_attempt.id IS '主键';
COMMENT ON COLUMN skill_flow_node_attempt.node_execution_id IS '节点执行 id,引用 skill_flow_node_execution(id);与 attempt_no 联合唯一';
COMMENT ON COLUMN skill_flow_node_attempt.attempt_no IS '尝试序号,从 1 递增';
COMMENT ON COLUMN skill_flow_node_attempt.status IS '该次尝试结果(SUCCESS/FAILED/CANCELLED 等)';
COMMENT ON COLUMN skill_flow_node_attempt.retryable IS '失败是否可重试';
COMMENT ON COLUMN skill_flow_node_attempt.error_code IS '错误码,可空';
COMMENT ON COLUMN skill_flow_node_attempt.error_message IS '错误信息,可空';
COMMENT ON COLUMN skill_flow_node_attempt.started_at IS '该次尝试开始时间';
COMMENT ON COLUMN skill_flow_node_attempt.completed_at IS '该次尝试结束时间,可空';
COMMENT ON COLUMN skill_flow_node_attempt.duration_ms IS '该次尝试耗时(毫秒),>=0,可空';
COMMENT ON COLUMN skill_flow_node_attempt.created_at IS '创建时间';

CREATE INDEX IF NOT EXISTS idx_skill_flow_node_attempt_node ON skill_flow_node_attempt (node_execution_id, attempt_no);

CREATE TABLE IF NOT EXISTS skill_flow_notification (
    id BIGSERIAL PRIMARY KEY,
    flow_execution_id BIGINT NOT NULL REFERENCES skill_flow_execution(id),
    delivery_key VARCHAR(256) NOT NULL,
    status VARCHAR(32) NOT NULL,
    recipient VARCHAR(256) NOT NULL,
    channel VARCHAR(32) NOT NULL,
    request_json TEXT NULL,
    response_json TEXT NULL,
    error_message TEXT NULL,
    sent_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    updated_at TIMESTAMP NOT NULL DEFAULT now(),
    UNIQUE (delivery_key)
);

COMMENT ON TABLE skill_flow_notification IS '流程级通知记录:子 Skill 不发单 Job 完成通知,只在父任务最终完成后通知一次;通知失败不影响任务状态,独立重试';
COMMENT ON COLUMN skill_flow_notification.id IS '主键';
COMMENT ON COLUMN skill_flow_notification.flow_execution_id IS '父任务 id,引用 skill_flow_execution(id)';
COMMENT ON COLUMN skill_flow_notification.delivery_key IS '投递防重键,全局唯一:初次完成通知为 flow:{executionId}:INITIAL,补发用请求 UUID';
COMMENT ON COLUMN skill_flow_notification.status IS '通知投递状态';
COMMENT ON COLUMN skill_flow_notification.recipient IS '通知接收人';
COMMENT ON COLUMN skill_flow_notification.channel IS '通知渠道';
COMMENT ON COLUMN skill_flow_notification.request_json IS '通知请求内容(JSON),可空';
COMMENT ON COLUMN skill_flow_notification.response_json IS '通知响应内容(JSON),可空';
COMMENT ON COLUMN skill_flow_notification.error_message IS '通知失败原因,可空';
COMMENT ON COLUMN skill_flow_notification.sent_at IS '发送成功时间,可空';
COMMENT ON COLUMN skill_flow_notification.created_at IS '创建时间';
COMMENT ON COLUMN skill_flow_notification.updated_at IS '更新时间';

CREATE INDEX IF NOT EXISTS idx_skill_flow_notification_execution ON skill_flow_notification (flow_execution_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_skill_flow_notification_status ON skill_flow_notification (status, created_at);
