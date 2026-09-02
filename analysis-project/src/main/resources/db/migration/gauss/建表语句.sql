-- ==================== 创建序列 ====================
CREATE SEQUENCE IF NOT EXISTS seq_developer_pl_person_info_id
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

-- ==================== developer_pl_person_info ====================
create table developer_pl_person_info
(
    id                           numeric default nextval('seq_developer_pl_person_info_id'::regclass) not null
        primary key,
    统一认证号                   varchar(64),
    类型                         varchar(20),
    统计月份                     varchar(20),
    部门                         varchar(50),
    姓名                         varchar(20),
    所属组                       varchar(60),
    考核人数                     varchar(50),
    adlm组                       varchar(100),
    三部处理组                   varchar(100),
    四部处理组                   varchar(100),
    统计组                       varchar(100),
    季度                         varchar(20),
    版本月份                     varchar(20),
    是否统计                     varchar(5),
    产品部测试人员对应开发部门   varchar(50),
    产品部测试人员对应开发统计组 varchar(50),
    岗位角色                     varchar(50),
    更新时间                     varchar(50),
    总行批复部门                 varchar(50),
    所属小团队                   varchar(100),
    产品部测试人员对应统计组     varchar(50),
    人员分类                     varchar(50),
    备注                         varchar(1000),
    实际行政组                   varchar(100),
    测试大组                     varchar(50),
    更新人                       varchar(50),
    备注_rms                     varchar(1000),
    linked_id                    varchar(100),
    是否设计骨干                 varchar(100),
    产品线                       varchar(100)
)
    with (orientation = row, compression = no);

comment on table developer_pl_person_info is '开发PL人员信息表';

comment on column developer_pl_person_info.id is '主键ID(自增序列)';

comment on column developer_pl_person_info.统一认证号 is '用户ID(原统一认证号,对应x-user-id)';

comment on column developer_pl_person_info.类型 is '类型';

comment on column developer_pl_person_info.统计月份 is '统计月份';

comment on column developer_pl_person_info.部门 is '部门';

comment on column developer_pl_person_info.姓名 is '姓名';

comment on column developer_pl_person_info.所属组 is '所属组';

comment on column developer_pl_person_info.考核人数 is '考核人数';

comment on column developer_pl_person_info.adlm组 is 'adlm组';

comment on column developer_pl_person_info.三部处理组 is '三部处理组';

comment on column developer_pl_person_info.四部处理组 is '四部处理组';

comment on column developer_pl_person_info.统计组 is '统计组';

comment on column developer_pl_person_info.季度 is '季度';

comment on column developer_pl_person_info.版本月份 is '版本月份';

comment on column developer_pl_person_info.是否统计 is '是否统计';

comment on column developer_pl_person_info.产品部测试人员对应开发部门 is '产品部测试人员对应开发部门';

comment on column developer_pl_person_info.产品部测试人员对应开发统计组 is '产品部测试人员对应开发统计组(原"产品部测试人员对应开发行政小组")';

comment on column developer_pl_person_info.岗位角色 is '岗位角色';

comment on column developer_pl_person_info.更新时间 is '更新时间';

comment on column developer_pl_person_info.总行批复部门 is '总行批复部门';

comment on column developer_pl_person_info.所属小团队 is '所属小团队';

comment on column developer_pl_person_info.产品部测试人员对应统计组 is '产品部测试人员对应统计组';

comment on column developer_pl_person_info.人员分类 is '人员分类';

comment on column developer_pl_person_info.备注 is '备注';

comment on column developer_pl_person_info.实际行政组 is '实际行政组';

comment on column developer_pl_person_info.测试大组 is '测试大组';

comment on column developer_pl_person_info.更新人 is '更新人';

comment on column developer_pl_person_info.备注_rms is '备注RMS';

comment on column developer_pl_person_info.linked_id is '关联ID';

comment on column developer_pl_person_info.是否设计骨干 is '是否设计骨干';

comment on column developer_pl_person_info.产品线 is '产品线(预留,后期关联其他表查询)';

alter table developer_pl_person_info
    owner to readwriter;

create index idx_developer_pl_person_info_user_id
    on developer_pl_person_info (统一认证号);

create index idx_developer_pl_person_info_month
    on developer_pl_person_info (统计月份);

create index idx_developer_pl_person_info_dept
    on developer_pl_person_info (部门);

-- ==================== skill_approver ====================
create table skill_approver
(
    id                  bigserial
        primary key,
    user_id             varchar(64)                                     not null,
    approver_name       varchar(50),
    approval_scope_type varchar(32)                                     not null,
    approval_scope_name varchar(100),
    status              varchar(16) default 'ACTIVE'::character varying not null,
    created_at          timestamp   default now()                       not null,
    updated_at          timestamp   default now()                       not null
)
    with (orientation = row, compression = no);

comment on table skill_approver is 'Skill审批人员表';

comment on column skill_approver.id is '主键ID';

comment on column skill_approver.user_id is '审批人工号(关联developer_pl_person_info.user_id)';

comment on column skill_approver.approver_name is '审批人姓名(冗余,来自developer_pl_person_info)';

comment on column skill_approver.approval_scope_type is '审批范围类型: GROUP/DEPARTMENT/PRODUCT_LINE/COMPANY';

comment on column skill_approver.approval_scope_name is '审批范围名称(如开发一组/研发部/数据产品线)';

comment on column skill_approver.status is '状态: ACTIVE/INACTIVE';

comment on column skill_approver.created_at is '创建时间';

comment on column skill_approver.updated_at is '更新时间';

alter table skill_approver
    owner to readwriter;

create unique index uk_approver_user_scope
    on skill_approver (user_id, approval_scope_type, approval_scope_name);

create index idx_approver_user_id
    on skill_approver (user_id);

create index idx_approver_scope
    on skill_approver (approval_scope_type, approval_scope_name);

-- ==================== skill_index ====================
create table skill_index
(
    id            bigserial primary key,
    name          varchar(128)                                              not null,
    fingerprint   varchar(255),
    description   text,
    version       integer     default 1                                     not null,
    usage_count   integer     default 0                                     not null,
    success_count integer     default 0                                     not null,
    failure_count integer     default 0                                     not null,
    last_used     timestamp,
    status        varchar(16) default 'active'::character varying           not null,
    source        varchar(16) default 'auto_synthesized'::character varying not null,
    owner_user_id varchar(64) default NULL::character varying,
    updated_at    timestamp   default now()                                 not null,
    constraint uk_skill_index_name unique (name)
)
    with (orientation = row, compression = no);

comment on table skill_index is 'Skill 检索注册表';

comment on column skill_index.id is '主键ID';

comment on column skill_index.name is 'Skill名称';

comment on column skill_index.fingerprint is 'L1 lookup key';

comment on column skill_index.source is 'skill origin: user_generated | auto_synthesized';

comment on column skill_index.owner_user_id is 'skill owner for isolation; NULL = global (auto_synthesized or legacy)';

alter table skill_index
    owner to readwriter;

create index idx_status
    on skill_index (status);

create index idx_source
    on skill_index (source);

create index idx_owner_user_id
    on skill_index (owner_user_id);

-- ==================== skill_candidate ====================
create table skill_candidate
(
    fingerprint   varchar(255)                                     not null
        primary key,
    user_id       varchar(64)                                      not null,
    hit_count     integer     default 0                            not null,
    last_query    text,
    last_trace_id varchar(64),
    metric_tag    varchar(64) default NULL::character varying,
    status        varchar(16) default 'pending'::character varying not null,
    synth_skill   varchar(128),
    updated_at    timestamp   default now()                        not null
)
    with (orientation = row, compression = no);

comment on table skill_candidate is '待蒸馏的 Skill 指纹暂存区';

alter table skill_candidate
    owner to readwriter;

create index idx_user_status
    on skill_candidate (user_id, status);

create index idx_hit_count
    on skill_candidate (hit_count desc);

create index idx_metric_tag
    on skill_candidate (metric_tag);

-- ==================== skill_manage ====================
create table skill_manage
(
    id             bigserial
        primary key,
    name           varchar(128)                                      not null,
    description    text,
    content        text,
    category       varchar(64),
    tags           varchar(512),
    owner_user_id  varchar(64)                                       not null,
    status         varchar(32) default 'ACTIVE'::character varying   not null,
    like_count     bigint      default 0                             not null,
    retrieval_name varchar(128),
    created_at     timestamp   default now()                         not null,
    updated_at     timestamp   default now()                         not null,
    deleted_at     timestamp,
    visibility     varchar(16) default 'PERSONAL'::character varying not null
)
    with (orientation = row, compression = no);

comment on table skill_manage is 'Skill 主表';

comment on column skill_manage.retrieval_name is '映射到 skill_index.name 的检索名，page_<id> 格式';

comment on column skill_manage.visibility is '可见性: PUBLIC=公开(所有可见) / PRIVATE=私有(owner+授权可见)';

alter table skill_manage
    owner to readwriter;

-- ==================== skill_like ====================
create table skill_like
(
    id         bigserial
        primary key,
    skill_id   bigint                  not null,
    user_id    varchar(64)             not null,
    created_at timestamp default now() not null
)
    with (orientation = row, compression = no);

comment on table skill_like is '点赞记录表';

alter table skill_like
    owner to readwriter;

create unique index uk_user_skill
    on skill_like (user_id, skill_id);

create index idx_skill
    on skill_like (skill_id);

create index idx_user
    on skill_like (user_id);

-- ==================== skill_reference ====================
create table skill_reference
(
    id              bigserial
        primary key,
    source_skill_id bigint                  not null,
    target_skill_id bigint                  not null,
    creator         varchar(64)             not null,
    created_at      timestamp default now() not null
)
    with (orientation = row, compression = no);

comment on table skill_reference is '引用关系表';

alter table skill_reference
    owner to readwriter;

create unique index uk_source_target_creator
    on skill_reference (source_skill_id, target_skill_id, creator);

create index idx_creator
    on skill_reference (creator);

create index idx_target
    on skill_reference (target_skill_id);

-- ==================== skill_publish ====================
create table skill_publish
(
    id                       bigserial
        primary key,
    skill_id                 bigint                                           not null,
    target_type              varchar(32)                                      not null,
    target_id                varchar(64)                                      not null,
    target_name              varchar(128)                                     not null,
    status                   varchar(32) default 'PENDING'::character varying not null,
    submitter                varchar(64)                                      not null,
    approver                 varchar(64),
    approve_time             timestamp,
    current_approver_user_id varchar(64),
    last_approval_comment    text,
    last_approval_at         timestamp,
    created_at               timestamp   default now()                        not null
)
    with (orientation = row, compression = no);

comment on table skill_publish is 'Skill 发布表';

alter table skill_publish
    owner to readwriter;

-- ==================== url_shortener ====================
create table url_shortener
(
    id           bigserial
        primary key,
    short_code   varchar(32)             not null,
    original_url varchar(2048)           not null,
    created_at   timestamp default now() not null,
    expires_at   timestamp
)
    with (orientation = row, compression = no);

alter table url_shortener
    owner to readwriter;

-- ==================== skill_user_disable ====================
create table skill_user_disable
(
    id         bigserial
        primary key,
    skill_id   bigint                  not null,
    user_id    varchar(64)             not null,
    created_at timestamp default now() not null
)
    with (orientation = row, compression = no);

comment on table skill_user_disable is '用户禁用Skill表';

comment on column skill_user_disable.id is '主键ID';

comment on column skill_user_disable.skill_id is 'Skill ID';

comment on column skill_user_disable.user_id is '用户ID';

comment on column skill_user_disable.created_at is '创建时间';

alter table skill_user_disable
    owner to readwriter;

-- ==================== user_model_config ====================
create table user_model_config
(
    user_id          varchar(64)                                     not null
        primary key,
    provider         varchar(32) default 'openai'::character varying not null,
    token            text                                            not null,
    model_name       varchar(128)                                    not null,
    request_url      varchar(512),
    expire_at        timestamp,
    last_notified_at timestamp,
    created_at       timestamp   default now()                       not null,
    updated_at       timestamp   default now()                       not null
)
    with (orientation = row, compression = no);

alter table user_model_config
    owner to readwriter;

-- ==================== skill_file ====================
create table skill_file
(
    id           bigserial
        primary key,
    user_id      varchar(64)  not null,
    filename     varchar(255) not null,
    storage_path varchar(512) not null
        unique,
    file_type    varchar(32)  not null,
    file_size    bigint       not null,
    description  varchar(512),
    created_at   timestamp default now(),
    updated_at   timestamp
)
    with (orientation = row, compression = no);

comment on table skill_file is '用户文件资源表';

comment on column skill_file.id is '文件ID';

comment on column skill_file.user_id is '所属用户';

comment on column skill_file.filename is '文件名';

comment on column skill_file.storage_path is '磁盘存储路径';

comment on column skill_file.file_type is '文件类型: PYTHON/SQL/PDF/WORD/OTHER';

comment on column skill_file.file_size is '字节数';

comment on column skill_file.description is '描述';

comment on column skill_file.created_at is '创建时间';

comment on column skill_file.updated_at is '更新时间(触发器维护)';

alter table skill_file
    owner to readwriter;

create index idx_skill_file_user
    on skill_file (user_id);

create index idx_skill_file_type
    on skill_file (file_type);

-- ==================== skill_file_reference ====================
create table skill_file_reference
(
    id             bigserial
        primary key,
    skill_id       bigint                                              not null,
    file_id        bigint                                              not null,
    reference_type varchar(32) default 'ATTACHMENT'::character varying not null,
    created_at     timestamp   default now(),
    constraint uq_skill_file_ref
        unique (skill_id, file_id)
)
    with (orientation = row, compression = no);

comment on table skill_file_reference is 'Skill与文件引用关系表';

comment on column skill_file_reference.id is '引用ID';

comment on column skill_file_reference.skill_id is 'Skill ID';

comment on column skill_file_reference.file_id is '文件ID';

comment on column skill_file_reference.reference_type is '引用类型: ATTACHMENT/EXECUTABLE(后期用)';

comment on column skill_file_reference.created_at is '创建时间';

alter table skill_file_reference
    owner to readwriter;

create index idx_skill_file_ref_skill
    on skill_file_reference (skill_id);

create index idx_skill_file_ref_file
    on skill_file_reference (file_id);

-- ==================== skill_approval ====================
create table skill_approval
(
    id               bigserial
        primary key,
    publish_id       bigint,
    draft_id         bigint,
    action           varchar(32)             not null,
    operator         varchar(64)             not null,
    comment          text,
    version_snapshot integer                 not null,
    created_at       timestamp default now() not null
)
    with (orientation = row, compression = no);

comment on table skill_approval is '审批操作记录表';

comment on column skill_approval.id is '主键ID';

comment on column skill_approval.publish_id is '发布记录ID';

comment on column skill_approval.draft_id is '草稿ID';

comment on column skill_approval.action is '操作: APPROVE/REJECT/SUBMIT';

comment on column skill_approval.operator is '操作者';

comment on column skill_approval.comment is '评论';

comment on column skill_approval.version_snapshot is '版本快照';

comment on column skill_approval.created_at is '创建时间';

alter table skill_approval
    owner to readwriter;

create index idx_publish
    on skill_approval (publish_id);

create index idx_draft
    on skill_approval (draft_id);

create index idx_operator
    on skill_approval (operator);

-- ==================== skill_operation_history ====================
create table skill_operation_history
(
    id          bigserial
        primary key,
    skill_id    bigint,
    publish_id  bigint,
    operator    varchar(64)             not null,
    operation   varchar(64)             not null,
    before_data text,
    after_data  text,
    created_at  timestamp default now() not null
)
    with (orientation = row, compression = no);

comment on table skill_operation_history is '操作历史表';

comment on column skill_operation_history.id is '主键ID';

comment on column skill_operation_history.skill_id is 'Skill ID';

comment on column skill_operation_history.publish_id is '发布记录ID';

comment on column skill_operation_history.operator is '操作者';

comment on column skill_operation_history.operation is '操作类型';

comment on column skill_operation_history.before_data is '操作前数据(JSON)';

comment on column skill_operation_history.after_data is '操作后数据(JSON)';

comment on column skill_operation_history.created_at is '创建时间';

alter table skill_operation_history
    owner to readwriter;

-- ==================== sql_registry ====================
create table sql_registry
(
    id            bigserial
        primary key,
    sql_id        varchar(64)             not null,
    name          varchar(128)            not null,
    description   varchar(500),
    datasource    varchar(16)             not null,
    sql_template  varchar(2000)           not null,
    params_schema varchar(2000),
    enabled       integer   default 1     not null,
    created_at    timestamp default now() not null,
    updated_at    timestamp default now() not null,
    created_by    varchar(64)
)
    with (orientation = row, compression = no);

alter table sql_registry
    owner to readwriter;

-- ==================== skill_dependency_metric ====================
create table skill_dependency_metric
(
    id                      bigserial
        primary key,
    code                    varchar(64)             not null,
    name                    varchar(128)            not null,
    description             text,
    enabled                 boolean   default true  not null,
    notify_enabled          boolean   default false not null,
    notify_content_type     varchar(16),
    notify_content_template text,
    created_at              timestamp default now() not null,
    updated_at              timestamp default now() not null
)
    with (orientation = row, compression = no);

comment on table skill_dependency_metric is '依赖指标（admin 预置只读，无 CRUD 接口）';

comment on column skill_dependency_metric.code is '业务编码（唯一），外部 triggerByMetric 用它';

comment on column skill_dependency_metric.enabled is '是否启用：false 后不可被新建 Job 选中且不可被触发';

comment on column skill_dependency_metric.notify_enabled is '跑批(METRIC触发)成功后是否发通知（admin 预置，默认 FALSE）';

comment on column skill_dependency_metric.notify_content_type is '通知内容格式：TEXT 纯文本 / HTML（默认 HTML）';

comment on column skill_dependency_metric.notify_content_template is '通知内容模板，支持 {job_name}/{metric_name} 等变量；为空用内置默认';

alter table skill_dependency_metric
    owner to readwriter;

create unique index uk_metric_code
    on skill_dependency_metric (code);

-- ==================== skill_job ====================
create table skill_job
(
    id                bigserial
        primary key,
    name              varchar(128)            not null,
    skill_id          bigint,
    question_template text,
    output_path       varchar(512),
    enabled           boolean   default true  not null,
    metric_id         bigint,
    batch_trigger     boolean   default true  not null,
    created_by        varchar(64)             not null,
    created_at        timestamp default now() not null,
    updated_at        timestamp default now() not null
)
    with (orientation = row, compression = no);

comment on table skill_job is 'SkillJob 任务配置，绑定 Skill + 依赖指标 + 提问模板 + MD 输出路径';

comment on column skill_job.name is '任务名称（唯一），外部 triggerByName 用它调起';

comment on column skill_job.metric_id is '依赖指标 ID；建 Job 时从预置列表选一个，参与 triggerByMetric 批量触发';

comment on column skill_job.batch_trigger is '是否参与指标批量触发；FALSE=仅记录依赖不随批量跑，仍可手动单发';

comment on column skill_job.created_by is '创建人 userId，定时/外部触发统一以此身份执行并校验 Skill 权限';

alter table skill_job
    owner to readwriter;

create unique index uk_skill_job_name
    on skill_job (name);

create index idx_skill_job_metric
    on skill_job (metric_id);

create index idx_skill_job_created_by
    on skill_job (created_by);

create index idx_skill_job_enabled
    on skill_job (enabled);

-- ==================== skill_job_execution ====================
create table skill_job_execution
(
    id                   bigserial
        primary key,
    job_id               bigint                                           not null,
    trigger_type         varchar(16),
    status               varchar(16) default 'PENDING'::character varying not null,
    conversation_id      varchar(128),
    resolved_output_path varchar(512),
    md_file_written      boolean     default false                        not null,
    md_file_exists       boolean     default false                        not null,
    error_msg            text,
    started_at           timestamp,
    completed_at         timestamp,
    created_at           timestamp   default now()                        not null
)
    with (orientation = row, compression = no);

comment on table skill_job_execution is 'SkillJob 执行记录，一次触发一条，重试不新建只更新同一条';

comment on column skill_job_execution.trigger_type is '触发类型：MANUAL(手动) / EXTERNAL(按名外部触发) / METRIC(按指标批量触发)';

comment on column skill_job_execution.status is '状态：PENDING/RUNNING/SUCCESS/FAILED/SKIPPED';

alter table skill_job_execution
    owner to readwriter;

create index idx_execution_job_id
    on skill_job_execution (job_id);

create index idx_execution_status
    on skill_job_execution (status);

create index idx_execution_created
    on skill_job_execution (created_at desc);

-- ==================== script_registry ====================
create table script_registry
(
    id              bigserial
        primary key,
    script_id       varchar(128)                                        not null
        constraint uk_script_registry_script_id
            unique,
    name            varchar(256)                                        not null,
    description     text,
    script_path     varchar(512)                                        not null,
    datasources     varchar(256) default '["gauss"]'::character varying not null,
    params_schema   text,
    timeout_seconds integer      default 60                             not null,
    enabled         smallint     default 1                              not null,
    created_at      timestamp    default pg_systimestamp()              not null,
    updated_at      timestamp    default pg_systimestamp()              not null,
    created_by      varchar(64)
)
    with (orientation = row, compression = no);

alter table script_registry
    owner to readwriter;

create index idx_script_registry_enabled
    on script_registry (enabled);

-- ==================== skill_visible_grant ====================
create table skill_visible_grant
(
    id         bigserial
        primary key,
    skill_id   bigint                  not null,
    grant_type varchar(16)             not null,
    target_id  varchar(64)             not null,
    granted_by varchar(64)             not null,
    created_at timestamp default now() not null
)
    with (orientation = row, compression = no);

comment on table skill_visible_grant is 'Skill私有可见性授权表';

comment on column skill_visible_grant.skill_id is 'Skill ID';

comment on column skill_visible_grant.grant_type is '授权类型: USER/DEPARTMENT/GROUP/VIRTUAL_GROUP';

comment on column skill_visible_grant.target_id is '授权目标: USER=统一认证号 / DEPARTMENT=部门名 / GROUP=统计组名 / VIRTUAL_GROUP=虚拟组名';

comment on column skill_visible_grant.granted_by is '授权人(owner)';

comment on column skill_visible_grant.created_at is '创建时间';

alter table skill_visible_grant
    owner to readwriter;

create unique index uk_skill_grant
    on skill_visible_grant (skill_id, grant_type, target_id);

create unique index uk_visible_grant_target
    on skill_visible_grant (skill_id, grant_type, target_id);

-- ==================== skill_job_notification ====================
create table skill_job_notification
(
    id                bigserial
        primary key,
    job_id            bigint                  not null,
    execution_id      bigint                  not null,
    request_type      varchar(16)             not null,
    status            varchar(16)             not null,
    trigger_type      varchar(16),
    sender_name       varchar(256),
    recipient_summary varchar(512),
    content_type      varchar(16),
    content           text,
    file_name         varchar(512),
    file_url          text,
    error_msg         text,
    requested_at      timestamp default now() not null,
    started_at        timestamp,
    completed_at      timestamp,
    created_at        timestamp default now() not null
)
    with (orientation = row, compression = no);

comment on table skill_job_notification is 'SkillJob 通知投递记录：每次首发/补发独立留痕';

comment on column skill_job_notification.request_type is 'INITIAL=任务完成首发 / RESEND=人工补发';

comment on column skill_job_notification.status is 'PENDING=已受理 / SENDING=调用中 / SUCCESS=发送器返回成功 / FAILED=发送器异常 / SKIPPED=配置不发送';

alter table skill_job_notification
    owner to readwriter;

create index idx_skill_job_notification_execution
    on skill_job_notification (execution_id asc, id desc);

create index idx_skill_job_notification_status
    on skill_job_notification (status asc, requested_at desc);

-- ==================== skill_virtual_group ====================
create table skill_virtual_group
(
    id         bigserial
        primary key,
    group_name varchar(128)            not null,
    user_id    varchar(64)             not null,
    created_by varchar(64),
    created_at timestamp default now() not null
)
    with (orientation = row, compression = no);

comment on table skill_virtual_group is '虚拟组成员表(组名+userid),私有Skill授权可按虚拟组命中';

comment on column skill_virtual_group.id is '主键ID';

comment on column skill_virtual_group.group_name is '虚拟组名(组的唯一标识)';

comment on column skill_virtual_group.user_id is '成员统一认证号';

comment on column skill_virtual_group.created_by is '建组/加成员操作人';

comment on column skill_virtual_group.created_at is '创建时间';

alter table skill_virtual_group
    owner to readwriter;

create unique index uk_virtual_group_member
    on skill_virtual_group (group_name, user_id);

create index idx_virtual_group_user
    on skill_virtual_group (user_id);

-- ==================== skill_virtual_group_def ====================
create table skill_virtual_group_def
(
    group_name varchar(128)            not null
        primary key,
    created_by varchar(64),
    created_at timestamp default now() not null
)
    with (orientation = row, compression = no);

comment on table skill_virtual_group_def is '虚拟组定义表(组头),一行=一个虚拟组;成员在 skill_virtual_group';

comment on column skill_virtual_group_def.group_name is '虚拟组名(组的唯一标识)';

comment on column skill_virtual_group_def.created_by is '建组人';

comment on column skill_virtual_group_def.created_at is '创建时间';

alter table skill_virtual_group_def
    owner to readwriter;

-- ==================== skill_flow_builtin_prompt ====================
create table skill_flow_builtin_prompt
(
    prompt_key     varchar(128) primary key,
    prompt_name    varchar(128) not null,
    prompt_content text         not null,
    enabled        boolean      default true not null,
    created_at     timestamp    default now() not null,
    updated_at     timestamp    default now() not null
);

comment on table skill_flow_builtin_prompt is '长任务内置提示词；用于初始化流程定义，不影响已有流程和执行快照';

insert into skill_flow_builtin_prompt (prompt_key, prompt_name, prompt_content)
values ('SKILL_FLOW_NODE_QUESTION_TEMPLATE', 'Skill 节点默认提示词', '你正在执行长任务流程「{flow_name}」中的一个独立 Skill 分析节点。

本次必须调用并只调用 Skill「{skill_name}」完成分析。
用户原始问题只作为业务背景，不代表本 Skill 需要回答全部问题。

请按以下规则执行：
1. 只分析 Skill「{skill_name}」职责范围内、与原始问题相关的内容。
2. 原始问题中不属于本 Skill 能力范围的部分，请直接忽略，不要扩展分析。
3. 不要替其他 Skill 下结论，不要做最终综合报告。
4. 如果原始问题与本 Skill 基本无关，请返回“本 Skill 未发现需要处理的相关内容”，并简要说明原因。
5. 输出本 Skill 的结构化分析结果，供后续汇总使用。

用户原始问题：
{original_question}'),
       ('SKILL_FLOW_SUMMARY_QUESTION_TEMPLATE', '最终汇总默认提示词', '你是长任务流程「{flow_name}」的最终汇总节点。

请基于以下各 Skill 节点结果，回答用户原始问题。
要求：
1. 只使用各 Skill 已产出的结果进行汇总，不要编造缺失数据。
2. 如果某个 Skill 返回“无相关内容”或执行失败，请在结论中自然说明影响。
3. 按用户问题组织最终报告，而不是按 Skill 机械堆叠。
4. 优先给出结论、关键指标、异常点、原因判断和建议动作。
5. 保留必要的数据口径说明。

用户原始问题：
{original_question}

各 Skill 节点结果：
{all_results}');

-- ==================== skill_flow ====================
create table skill_flow
(
    id                        bigserial
        primary key,
    code                      varchar(128)            not null
        unique,
    name                      varchar(128)            not null,
    description               text,
    summary_question_template text                    not null,
    enabled                   boolean   default false not null,
    max_parallelism           integer   default 4     not null
        constraint skill_flow_max_parallelism_check
            check (max_parallelism > 0),
    notify_enabled            boolean   default true  not null,
    created_by                varchar(64)             not null,
    created_at                timestamp default now() not null,
    updated_at                timestamp default now() not null,
    deleted_at                timestamp
)
    with (orientation = row, compression = no);

comment on table skill_flow is '长任务流程定义表(流程头),一行=一个多 Skill 流程;节点在 skill_flow_node';

comment on column skill_flow.id is '主键';

comment on column skill_flow.code is '流程编码,全局唯一';

comment on column skill_flow.name is '流程名称';

comment on column skill_flow.description is '流程描述,可空';

comment on column skill_flow.summary_question_template is '最终汇总问题模板,全部 Skill 终态后渲染执行';

comment on column skill_flow.enabled is '是否启用,启用后触发关键词才会匹配';

comment on column skill_flow.max_parallelism is '流程内 Skill 节点最大并发数,>0';

comment on column skill_flow.notify_enabled is '流程完成是否发送通知';

comment on column skill_flow.created_by is '创建人';

comment on column skill_flow.created_at is '创建时间';

comment on column skill_flow.updated_at is '更新时间';

comment on column skill_flow.deleted_at is '软删除时间,非空即已删除';

alter table skill_flow
    owner to readwriter;

create index idx_skill_flow_enabled
    on skill_flow (enabled, deleted_at);

-- ==================== skill_flow_node ====================
create table skill_flow_node
(
    id                bigserial
        primary key,
    flow_id           bigint                       not null
        references skill_flow,
    node_key          varchar(128)                 not null,
    skill_id          bigint                       not null
        references skill_manage,
    question_template text                         not null,
    depends_on_json   text      default '[]'::text not null,
    required          boolean   default true       not null,
    max_attempts      integer   default 4          not null
        constraint skill_flow_node_max_attempts_check
            check (max_attempts > 0),
    sort_order        integer   default 0          not null,
    created_at        timestamp default now()      not null,
    updated_at        timestamp default now()      not null,
    unique (flow_id, node_key)
)
    with (orientation = row, compression = no);

comment on table skill_flow_node is '长任务流程节点定义表,一行=一个 Skill 节点;依赖关系构成 DAG(不支持循环/条件分支)';

comment on column skill_flow_node.id is '主键';

comment on column skill_flow_node.flow_id is '所属流程 id,引用 skill_flow(id)';

comment on column skill_flow_node.node_key is '节点 key,流程内唯一;depends_on_json 引用它';

comment on column skill_flow_node.skill_id is '节点绑定的 Skill,引用 skill_manage(id)';

comment on column skill_flow_node.question_template is '该节点的问题模板,渲染后作为 Skill 的输入问题';

comment on column skill_flow_node.depends_on_json is '前置节点 key 数组(JSON),空数组=门闩打开后可直接并行执行';

comment on column skill_flow_node.required is '是否必需节点:必需失败则任务 FAILED,可选失败只影响 PARTIAL_SUCCESS';

comment on column skill_flow_node.max_attempts is '节点最大尝试次数(含首次),>0';

comment on column skill_flow_node.sort_order is '展示排序号';

comment on column skill_flow_node.created_at is '创建时间';

comment on column skill_flow_node.updated_at is '更新时间';

alter table skill_flow_node
    owner to readwriter;

create index idx_skill_flow_node_flow_order
    on skill_flow_node (flow_id, sort_order);

create index idx_skill_flow_node_skill
    on skill_flow_node (skill_id);

-- ==================== skill_flow_node_metric ====================
create table skill_flow_node_metric
(
    id           bigserial
        primary key,
    flow_node_id bigint                  not null
        references skill_flow_node,
    metric_id    bigint                  not null
        references skill_dependency_metric,
    created_at   timestamp default now() not null,
    unique (flow_node_id, metric_id)
)
    with (orientation = row, compression = no);

comment on table skill_flow_node_metric is '节点依赖指标关系表:节点执行前其全部依赖指标须 READY(指标门闩)';

comment on column skill_flow_node_metric.id is '主键';

comment on column skill_flow_node_metric.flow_node_id is '节点 id,引用 skill_flow_node(id)';

comment on column skill_flow_node_metric.metric_id is '指标 id,引用 skill_dependency_metric(id)';

comment on column skill_flow_node_metric.created_at is '创建时间';

alter table skill_flow_node_metric
    owner to readwriter;

create index idx_skill_flow_node_metric_metric
    on skill_flow_node_metric (metric_id, flow_node_id);

-- ==================== skill_flow_trigger ====================
create table skill_flow_trigger
(
    id                 bigserial
        primary key,
    flow_id            bigint                  not null
        references skill_flow,
    keyword            varchar(256)            not null,
    normalized_keyword varchar(256)            not null
        unique,
    priority           integer   default 0     not null,
    enabled            boolean   default true  not null,
    created_by         varchar(64)             not null,
    created_at         timestamp default now() not null,
    updated_at         timestamp default now() not null
)
    with (orientation = row, compression = no);

comment on table skill_flow_trigger is '触发关键词表:消息 CONTAINS 命中即创建长任务(V1 不开放正则);同一消息命中多个流程时取 priority 最高,同优先级按 id 升序';

comment on column skill_flow_trigger.id is '主键,同优先级冲突时的决胜键(升序)';

comment on column skill_flow_trigger.flow_id is '命中后创建的流程 id,引用 skill_flow(id)';

comment on column skill_flow_trigger.keyword is '触发关键词原文';

comment on column skill_flow_trigger.normalized_keyword is '规范化关键词,全局唯一:同一关键词不允许关联多个流程';

comment on column skill_flow_trigger.priority is '优先级,越大越先命中;不同关键词命中不同流程时取最高者';

comment on column skill_flow_trigger.enabled is '是否启用';

comment on column skill_flow_trigger.created_by is '创建人';

comment on column skill_flow_trigger.created_at is '创建时间';

comment on column skill_flow_trigger.updated_at is '更新时间';

alter table skill_flow_trigger
    owner to readwriter;

create index idx_skill_flow_trigger_enabled_priority
    on skill_flow_trigger (enabled asc, priority desc, id asc);

create index idx_skill_flow_trigger_flow
    on skill_flow_trigger (flow_id);

-- ==================== skill_metric_readiness ====================
create table skill_metric_readiness
(
    id            bigserial
        primary key,
    metric_id     bigint                  not null
        references skill_dependency_metric,
    metric_code   varchar(128)            not null,
    data_date     date                    not null,
    status        varchar(16)             not null
        constraint skill_metric_readiness_status_check
            check ((status)::text = ANY ((ARRAY ['READY'::character varying, 'EXPIRED'::character varying])::text[])),
    ready_at      timestamp               not null,
    expires_at    timestamp               not null,
    metadata_json text,
    created_at    timestamp default now() not null,
    updated_at    timestamp default now() not null,
    unique (metric_id, data_date)
)
    with (orientation = row, compression = no);

comment on table skill_metric_readiness is '指标每日就绪事实表:一行=某指标某数据日的是否就绪;只记事实,不表示 Skill 已执行';

comment on column skill_metric_readiness.id is '主键';

comment on column skill_metric_readiness.metric_id is '指标 id,引用 skill_dependency_metric(id);与 data_date 联合唯一';

comment on column skill_metric_readiness.metric_code is '指标编码(冗余,便于按 code 查询)';

comment on column skill_metric_readiness.data_date is '数据日期';

comment on column skill_metric_readiness.status is '就绪状态:READY(就绪)/EXPIRED(已过期)';

comment on column skill_metric_readiness.ready_at is '就绪时间';

comment on column skill_metric_readiness.expires_at is '就绪有效期截止时间,过期后按 EXPIRED 处理';

comment on column skill_metric_readiness.metadata_json is '就绪元数据(JSON),可空';

comment on column skill_metric_readiness.created_at is '创建时间';

comment on column skill_metric_readiness.updated_at is '更新时间';

alter table skill_metric_readiness
    owner to readwriter;

create index idx_skill_metric_readiness_code_date
    on skill_metric_readiness (metric_code, data_date, status);

create index idx_skill_metric_readiness_expiry
    on skill_metric_readiness (expires_at, status);

-- ==================== skill_flow_execution ====================
create table skill_flow_execution
(
    id                                 bigserial
        primary key,
    flow_id                            bigint                       not null
        references skill_flow,
    flow_code                          varchar(128)                 not null,
    flow_name                          varchar(128)                 not null,
    summary_question_template_snapshot text                         not null,
    max_parallelism_snapshot           integer   default 4          not null
        constraint skill_flow_execution_max_parallelism_snapshot_check
            check (max_parallelism_snapshot > 0),
    notify_enabled_snapshot            boolean   default true       not null,
    trigger_type                       varchar(32)                  not null,
    trigger_user_id                    varchar(64)                  not null,
    conversation_id                    varchar(128)                 not null,
    original_question                  text                         not null,
    data_date                          date                         not null,
    status                             varchar(32)                  not null,
    active_guard_key                   varchar(512)
        unique,
    required_metric_count              integer   default 0          not null
        constraint skill_flow_execution_required_metric_count_check
            check (required_metric_count >= 0),
    ready_metric_count                 integer   default 0          not null
        constraint skill_flow_execution_ready_metric_count_check
            check (ready_metric_count >= 0),
    missing_metrics_json               text      default '[]'::text not null,
    summary_json                       text,
    report_path                        varchar(1024),
    cancel_requested_at                timestamp,
    started_at                         timestamp,
    completed_at                       timestamp,
    created_at                         timestamp default now()      not null,
    updated_at                         timestamp default now()      not null
)
    with (orientation = row, compression = no);

comment on table skill_flow_execution is '长任务父执行记录:一行=一次长任务;创建即持久化(不等指标),终态后同 guard key 可再建新任务';

comment on column skill_flow_execution.id is '主键,即返回给前端的 taskId';

comment on column skill_flow_execution.flow_id is '流程 id,引用 skill_flow(id)';

comment on column skill_flow_execution.flow_code is '流程编码快照(创建时刻的值)';

comment on column skill_flow_execution.flow_name is '流程名称快照';

comment on column skill_flow_execution.summary_question_template_snapshot is '汇总问题模板快照:流程事后修改不影响运行中任务';

comment on column skill_flow_execution.max_parallelism_snapshot is '最大并发数快照,>0';

comment on column skill_flow_execution.notify_enabled_snapshot is '是否通知快照';

comment on column skill_flow_execution.trigger_type is '触发类型,如关键词触发/手动触发';

comment on column skill_flow_execution.trigger_user_id is '触发人用户 id';

comment on column skill_flow_execution.conversation_id is '触发消息所在会话 id';

comment on column skill_flow_execution.original_question is '触发时的用户原始问题';

comment on column skill_flow_execution.data_date is '本次任务的数据日期(指标就绪按该日判定)';

comment on column skill_flow_execution.status is '状态。非终态:WAITING_METRICS(等指标)/QUEUED(等 worker)/RUNNING(执行中)/CANCEL_REQUESTED(用户要求直接回答)/SUMMARIZING(汇总中);终态:SUCCESS(全部成功)/PARTIAL_SUCCESS(必需成功+可选失败或阻塞)/FAILED(必需失败/阻塞/当天未集齐指标)/CANCELLED(用户取消)';

comment on column skill_flow_execution.active_guard_key is '活动任务防重键:userId+conversationId+flowId+dataDate 规范化生成,唯一约束;进入终态时清空(允许重建),并发创建冲突时查现有 execution 返回而非先查再插';

comment on column skill_flow_execution.required_metric_count is '任务依赖的指标总数';

comment on column skill_flow_execution.ready_metric_count is '已 READY 的指标数';

comment on column skill_flow_execution.missing_metrics_json is '未就绪指标清单(JSON 数组),默认 []';

comment on column skill_flow_execution.summary_json is '最终汇总结果(JSON),可空';

comment on column skill_flow_execution.report_path is '汇总报告文件路径,可空';

comment on column skill_flow_execution.cancel_requested_at is '用户请求取消(直接回答)的时间,可空';

comment on column skill_flow_execution.started_at is '开始执行时间(Skill 首次运行前),可空';

comment on column skill_flow_execution.completed_at is '进入终态的时间,可空';

comment on column skill_flow_execution.created_at is '创建时间';

comment on column skill_flow_execution.updated_at is '更新时间';

alter table skill_flow_execution
    owner to readwriter;

create index idx_skill_flow_execution_status_date
    on skill_flow_execution (status, data_date, created_at);

create index idx_skill_flow_execution_conversation_user
    on skill_flow_execution (conversation_id asc, trigger_user_id asc, created_at desc);

create index idx_skill_flow_execution_flow
    on skill_flow_execution (flow_id asc, created_at desc);

-- ==================== skill_flow_node_execution ====================
create table skill_flow_node_execution
(
    id                         bigserial
        primary key,
    flow_execution_id          bigint                       not null
        references skill_flow_execution,
    node_key                   varchar(128)                 not null,
    skill_id                   bigint                       not null,
    skill_name                 varchar(128)                 not null,
    skill_retrieval_name       varchar(256)                 not null,
    question_template_snapshot text                         not null,
    rendered_question          text,
    depends_on_json            text      default '[]'::text not null,
    required                   boolean   default true       not null,
    status                     varchar(32)                  not null,
    attempt_count              integer   default 0          not null
        constraint skill_flow_node_execution_attempt_count_check
            check (attempt_count >= 0),
    max_attempts               integer                      not null
        constraint skill_flow_node_execution_max_attempts_check
            check (max_attempts > 0),
    next_run_at                timestamp,
    lease_owner                varchar(128),
    lease_expires_at           timestamp,
    result_json                text,
    artifact_path              varchar(1024),
    error_code                 varchar(64),
    error_message              text,
    started_at                 timestamp,
    completed_at               timestamp,
    created_at                 timestamp default now()      not null,
    updated_at                 timestamp default now()      not null,
    unique (flow_execution_id, node_key)
)
    with (orientation = row, compression = no);

comment on table skill_flow_node_execution is '长任务节点执行快照:一行=一次任务中某 Skill 节点的执行;模板/依赖取快照,流程修改不影响运行中任务;所有节点必须进入终态';

comment on column skill_flow_node_execution.id is '主键';

comment on column skill_flow_node_execution.flow_execution_id is '所属父任务 id,引用 skill_flow_execution(id);与 node_key 联合唯一';

comment on column skill_flow_node_execution.node_key is '节点 key(创建时快照)';

comment on column skill_flow_node_execution.skill_id is 'Skill id 快照(无外键,历史任务允许引用已删 Skill)';

comment on column skill_flow_node_execution.skill_name is 'Skill 名称快照,用于展示/审计';

comment on column skill_flow_node_execution.skill_retrieval_name is 'Skill 检索名快照(调用执行时使用)';

comment on column skill_flow_node_execution.question_template_snapshot is '问题模板快照';

comment on column skill_flow_node_execution.rendered_question is '渲染后的实际问题,便于审计 Skill 实际收到的问题,可空';

comment on column skill_flow_node_execution.depends_on_json is '前置节点 key 数组快照(JSON),默认 []';

comment on column skill_flow_node_execution.required is '是否必需节点快照';

comment on column skill_flow_node_execution.status is '状态。非终态:PENDING(等门闩/等前置)/QUEUED(等 worker)/RUNNING(执行中)/RETRY_WAIT(等下次重试);终态:SUCCESS/FAILED/BLOCKED(前置必需失败,不能运行)/CANCELLED';

comment on column skill_flow_node_execution.attempt_count is '已实际执行次数,>=0;等待指标/依赖和排队不计入';

comment on column skill_flow_node_execution.max_attempts is '最大尝试次数快照,>0';

comment on column skill_flow_node_execution.next_run_at is '下次可运行时间(RETRY_WAIT 的退避/排队依据),可空';

comment on column skill_flow_node_execution.lease_owner is '执行租约持有者标识(多 worker 抢占/防重复执行),可空';

comment on column skill_flow_node_execution.lease_expires_at is '租约过期时间,过期后可被其他 worker 接管,可空';

comment on column skill_flow_node_execution.result_json is '节点执行结果(JSON),可空';

comment on column skill_flow_node_execution.artifact_path is '节点产物文件路径,可空';

comment on column skill_flow_node_execution.error_code is '错误码,可空';

comment on column skill_flow_node_execution.error_message is '错误信息,可空';

comment on column skill_flow_node_execution.started_at is '首次开始执行时间,可空';

comment on column skill_flow_node_execution.completed_at is '进入终态时间,可空';

comment on column skill_flow_node_execution.created_at is '创建时间';

comment on column skill_flow_node_execution.updated_at is '更新时间';

alter table skill_flow_node_execution
    owner to readwriter;

create index idx_skill_flow_node_execution_runnable
    on skill_flow_node_execution (status, next_run_at);

create index idx_skill_flow_node_execution_lease
    on skill_flow_node_execution (lease_expires_at, status);

create index idx_skill_flow_node_execution_flow
    on skill_flow_node_execution (flow_execution_id, status);

-- ==================== skill_flow_node_attempt ====================
create table skill_flow_node_attempt
(
    id                bigserial
        primary key,
    node_execution_id bigint                  not null
        references skill_flow_node_execution,
    attempt_no        integer                 not null
        constraint skill_flow_node_attempt_attempt_no_check
            check (attempt_no > 0),
    status            varchar(32)             not null,
    retryable         boolean   default false not null,
    error_code        varchar(64),
    error_message     text,
    started_at        timestamp               not null,
    completed_at      timestamp,
    duration_ms       bigint
        constraint skill_flow_node_attempt_duration_ms_check
            check ((duration_ms IS NULL) OR (duration_ms >= 0)),
    created_at        timestamp default now() not null,
    unique (node_execution_id, attempt_no)
)
    with (orientation = row, compression = no);

comment on table skill_flow_node_attempt is '节点尝试审计表:一行=一次实际执行;等待指标、等待依赖和排队不创建 attempt';

comment on column skill_flow_node_attempt.id is '主键';

comment on column skill_flow_node_attempt.node_execution_id is '节点执行 id,引用 skill_flow_node_execution(id);与 attempt_no 联合唯一';

comment on column skill_flow_node_attempt.attempt_no is '尝试序号,从 1 递增';

comment on column skill_flow_node_attempt.status is '该次尝试结果(SUCCESS/FAILED/CANCELLED 等)';

comment on column skill_flow_node_attempt.retryable is '失败是否可重试';

comment on column skill_flow_node_attempt.error_code is '错误码,可空';

comment on column skill_flow_node_attempt.error_message is '错误信息,可空';

comment on column skill_flow_node_attempt.started_at is '该次尝试开始时间';

comment on column skill_flow_node_attempt.completed_at is '该次尝试结束时间,可空';

comment on column skill_flow_node_attempt.duration_ms is '该次尝试耗时(毫秒),>=0,可空';

comment on column skill_flow_node_attempt.created_at is '创建时间';

alter table skill_flow_node_attempt
    owner to readwriter;

create index idx_skill_flow_node_attempt_node
    on skill_flow_node_attempt (node_execution_id, attempt_no);

-- ==================== skill_flow_notification ====================
create table skill_flow_notification
(
    id                bigserial
        primary key,
    flow_execution_id bigint                  not null
        references skill_flow_execution,
    delivery_key      varchar(256)            not null
        unique,
    status            varchar(32)             not null,
    recipient         varchar(256)            not null,
    channel           varchar(32)             not null,
    request_json      text,
    response_json     text,
    error_message     text,
    sent_at           timestamp,
    created_at        timestamp default now() not null,
    updated_at        timestamp default now() not null
)
    with (orientation = row, compression = no);

comment on table skill_flow_notification is '流程级通知记录:子 Skill 不发单 Job 完成通知,只在父任务最终完成后通知一次;通知失败不影响任务状态,独立重试';

comment on column skill_flow_notification.id is '主键';

comment on column skill_flow_notification.flow_execution_id is '父任务 id,引用 skill_flow_execution(id)';

comment on column skill_flow_notification.delivery_key is '投递防重键,全局唯一:初次完成通知为 flow:{executionId}:INITIAL,补发用请求 UUID';

comment on column skill_flow_notification.status is '通知投递状态';

comment on column skill_flow_notification.recipient is '通知接收人';

comment on column skill_flow_notification.channel is '通知渠道';

comment on column skill_flow_notification.request_json is '通知请求内容(JSON),可空';

comment on column skill_flow_notification.response_json is '通知响应内容(JSON),可空';

comment on column skill_flow_notification.error_message is '通知失败原因,可空';

comment on column skill_flow_notification.sent_at is '发送成功时间,可空';

comment on column skill_flow_notification.created_at is '创建时间';

comment on column skill_flow_notification.updated_at is '更新时间';

alter table skill_flow_notification
    owner to readwriter;

create index idx_skill_flow_notification_execution
    on skill_flow_notification (flow_execution_id asc, created_at desc);

create index idx_skill_flow_notification_status
    on skill_flow_notification (status, created_at);


ALTER TABLE skill_job_execution ADD COLUMN  report_markdown TEXT;
COMMENT ON COLUMN skill_job_execution.report_markdown IS '独立任务最终 Markdown 源，文件丢失时用于重新渲染 HTML';


ALTER TABLE skill_job
    ADD COLUMN  schedule_rules VARCHAR(256);

ALTER TABLE skill_flow
    ADD COLUMN  schedule_rules VARCHAR(256);