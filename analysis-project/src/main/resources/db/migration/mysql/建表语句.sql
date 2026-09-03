create table QualitySupervisor_episodic_memory
(
    id                bigint auto_increment
        primary key,
    session_id        varchar(255)                           not null,
    user_id           varchar(128) default ''                not null,
    role              varchar(50)                            not null,
    content           text                                   not null,
    tool_call_details text                                   null comment '工具调用链路详情JSON,供skill蒸馏使用',
    embedding         longtext                               null,
    status            varchar(16)  default 'active'          null,
    created_at        timestamp    default CURRENT_TIMESTAMP null
)
    charset = utf8mb4;

create fulltext index ft_content
    on QualitySupervisor_episodic_memory (content);

create index idx_embedding
    on QualitySupervisor_episodic_memory (embedding(255));

create index idx_status
    on QualitySupervisor_episodic_memory (status);

create index idx_user_id
    on QualitySupervisor_episodic_memory (user_id);

create table agent_memory
(
    id         bigint auto_increment
        primary key,
    user_id    varchar(128)                        not null,
    kind       varchar(32)                         not null,
    key_name   varchar(128)                        not null,
    body       mediumtext                          not null,
    version    int       default 1                 not null,
    updated_at timestamp default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP,
    constraint uk_user_kind_key
        unique (user_id, kind, key_name)
)
    charset = utf8mb4;

create index idx_user_updated
    on agent_memory (user_id, updated_at);

create table agent_memory_ledger
(
    id         bigint auto_increment
        primary key,
    user_id    varchar(128)                        not null,
    date_key   varchar(16)                         not null,
    source     varchar(32)                         not null,
    line       mediumtext                          not null,
    created_at timestamp default CURRENT_TIMESTAMP not null
)
    charset = utf8mb4;

create index idx_user_date
    on agent_memory_ledger (user_id, date_key, id);

create table agent_version_registry
(
    version_id     varchar(128)                             not null
        primary key,
    component      varchar(32)                              not null comment 'AGENT/PROMPT/SKILL/SEMANTIC_CONTRACT/REPAIR_POLICY/TOOL',
    component_ref  varchar(256)                             not null,
    version        varchar(32)                              not null,
    checksum       varchar(64)                              null,
    released_by    varchar(64)                              null,
    released_at    datetime(3) default CURRENT_TIMESTAMP(3) not null,
    golden_eval_id varchar(64)                              null,
    status         varchar(16) default 'candidate'          not null comment 'candidate/stable/deprecated'
)
    charset = utf8mb4;

create index idx_avr_component
    on agent_version_registry (component, status);

create table agentscope_sessions
(
    session_id varchar(255)                       not null,
    state_key  varchar(255)                       not null,
    item_index int      default 0                 not null,
    state_data longtext                           not null,
    created_at datetime default CURRENT_TIMESTAMP null,
    updated_at datetime default CURRENT_TIMESTAMP null on update CURRENT_TIMESTAMP,
    primary key (session_id, state_key, item_index)
);

create table agui_run_record
(
    id                bigint auto_increment comment '主键'
        primary key,
    thread_id         varchar(64)                         not null comment '会话ID',
    user_id           varchar(64)                         null comment '用户ID',
    agent_name        varchar(128)                        null comment 'Agent名称',
    status            varchar(32)                         not null comment 'RUNNING/COMPLETED/ERROR',
    total_duration_ms int                                 null comment '总耗时毫秒',
    event_count       int                                 null comment '事件总数',
    events_json       longtext                            null comment '完整事件JSON数组',
    created_at        timestamp default CURRENT_TIMESTAMP null comment '创建时间'
)
    comment 'AG-UI运行记录表' charset = utf8mb4;

create index idx_created_at
    on agui_run_record (created_at);

create index idx_thread_id
    on agui_run_record (thread_id);

create index idx_user_id
    on agui_run_record (user_id);

create table calibration_apply_pending
(
    id          bigint auto_increment
        primary key,
    eval_id     varchar(64)                              not null,
    pass_before int                                      not null,
    warn_before int                                      not null,
    started_at  datetime(3) default CURRENT_TIMESTAMP(3) not null,
    status      varchar(16) default 'pending'            not null comment 'pending/validated/rolled_back',
    resolved_at datetime(3)                              null,
    constraint uk_cap_eval
        unique (eval_id)
)
    charset = utf8mb4;

create index idx_cap_status
    on calibration_apply_pending (status);

create table calibration_state
(
    id               int         default 1                    not null
        primary key,
    pass_threshold   int                                      not null,
    warn_threshold   int                                      not null,
    direct_threshold int                                      not null,
    hint_threshold   int                                      not null,
    w_data           double                                   not null,
    w_tool           double                                   not null,
    w_semantic       double                                   not null,
    w_adversarial    double                                   not null,
    updated_at       datetime(3) default CURRENT_TIMESTAMP(3) not null on update CURRENT_TIMESTAMP(3)
)
    charset = utf8mb4;

create table critic_challenge_stats
(
    challenge_type  varchar(64)                              not null
        primary key,
    found_count     int         default 0                    not null,
    confirmed_count int         default 0                    not null,
    updated_at      datetime(3) default CURRENT_TIMESTAMP(3) not null on update CURRENT_TIMESTAMP(3)
)
    charset = utf8mb4;

create table digestion_log
(
    id                     bigint auto_increment
        primary key,
    user_id                varchar(128)         not null,
    date_key               varchar(16)          not null,
    phase1_cleaned_ledger  int        default 0 null,
    phase2_mined_traces    int        default 0 null,
    phase3_skills_evolved  int        default 0 null,
    phase4_memory_digested tinyint(1) default 0 null,
    started_at             timestamp            not null,
    completed_at           timestamp            null,
    error_msg              text                 null
)
    charset = utf8mb4;

create index idx_user_date
    on digestion_log (user_id, date_key);

create table flyway_schema_history
(
    installed_rank int                                 not null
        primary key,
    version        varchar(50)                         null,
    description    varchar(200)                        not null,
    type           varchar(20)                         not null,
    script         varchar(1000)                       not null,
    checksum       int                                 null,
    installed_by   varchar(100)                        not null,
    installed_on   timestamp default CURRENT_TIMESTAMP not null,
    execution_time int                                 not null,
    success        tinyint(1)                          not null
);

create index flyway_schema_history_s_idx
    on flyway_schema_history (success);

create table golden_dataset_case
(
    case_id         varchar(64)              not null
        primary key,
    question        text                     not null,
    category        varchar(64)              null,
    expected_sql    text                     null,
    expected_answer text                     null,
    expected_metric varchar(64)              null,
    difficulty      varchar(8)               null comment 'LOW/MEDIUM/HIGH',
    tags            varchar(256)             null,
    version         varchar(32) default 'v1' not null
)
    charset = utf8mb4;

create table golden_evaluation_result
(
    id                 bigint auto_increment
        primary key,
    eval_id            varchar(64)                              not null,
    case_id            varchar(64)                              not null,
    agent_version      varchar(32)                              null,
    prompt_version     varchar(32)                              null,
    skill_version      varchar(32)                              null,
    semantic_version   varchar(32)                              null,
    actual_answer      text                                     null,
    trust_score        int                                      null,
    verdict            varchar(16)                              null,
    accuracy_pass      tinyint     default 0                    null,
    semantic_pass      tinyint     default 0                    null,
    hallucination_flag tinyint     default 0                    null,
    repair_used        varchar(32)                              null,
    created_at         datetime(3) default CURRENT_TIMESTAMP(3) not null
)
    charset = utf8mb4;

create index idx_ger_eval
    on golden_evaluation_result (eval_id);

create index idx_ger_version
    on golden_evaluation_result (agent_version, prompt_version);

create table repair_execution_history
(
    id             bigint auto_increment
        primary key,
    session_id     varchar(128)                             not null,
    loop_index     int         default 0                    null,
    error_type     varchar(64)                              null,
    repair_type    varchar(32)                              null,
    directive      text                                     null,
    forbidden_hit  tinyint     default 0                    not null comment 'gaming suspect flag',
    gaming_suspect tinyint     default 0                    not null,
    outcome        varchar(32)                              null,
    created_at     datetime(3) default CURRENT_TIMESTAMP(3) not null
)
    charset = utf8mb4;

create index idx_reh_gaming
    on repair_execution_history (gaming_suspect);

create index idx_reh_session
    on repair_execution_history (session_id);

create table repair_policy_rule
(
    id                   bigint auto_increment
        primary key,
    rule_id              varchar(64)       not null,
    error_type           varchar(64)       not null,
    severity             varchar(16)       not null comment 'LOW/MEDIUM/HIGH/CRITICAL',
    allowed_actions_json text              not null,
    forbidden_json       text              null,
    max_retry            int     default 1 not null,
    priority             int     default 0 not null,
    enabled              tinyint default 1 not null,
    constraint uk_rpr_rule
        unique (rule_id)
)
    charset = utf8mb4;

create table response_cache
(
    id         bigint auto_increment
        primary key,
    cache_key  varchar(512)                        not null,
    question   varchar(1024)                       not null,
    response   mediumtext                          not null,
    created_at timestamp default CURRENT_TIMESTAMP null,
    expire_at  timestamp                           not null,
    constraint uk_cache_key
        unique (cache_key)
)
    charset = utf8mb4;

create index idx_expire
    on response_cache (expire_at);

create table rule_experiment
(
    experiment_id              varchar(64)                              not null
        primary key,
    name                       varchar(128)                             not null,
    candidate_metric_id        varchar(64)                              not null,
    candidate_direction        varchar(16)                              not null comment 'worse|better',
    candidate_deny_aggregation varchar(64)                              null comment 'e.g. sum (nullable)',
    traffic_percent            int         default 0                    not null comment '0-100 bucket size',
    status                     varchar(16) default 'running'            not null comment 'running/promoted/rolled_back',
    started_at                 datetime(3) default CURRENT_TIMESTAMP(3) not null,
    ended_at                   datetime(3)                              null
)
    charset = utf8mb4;

create index idx_re_status
    on rule_experiment (status);

create table semantic_business_rule
(
    rule_id     varchar(64)                  not null
        primary key,
    `condition` text                         not null,
    description text                         null,
    version     varchar(32) default 'v1'     not null,
    status      varchar(16) default 'active' not null
)
    charset = utf8mb4;

create table semantic_dimension_contract
(
    id                  bigint auto_increment
        primary key,
    dimension           varchar(64)                  not null,
    allowed_values_json text                         null,
    hierarchy_json      text                         null,
    version             varchar(32) default 'v1'     not null,
    status              varchar(16) default 'active' not null,
    constraint uk_sdc_dim
        unique (dimension, version)
)
    charset = utf8mb4;

create table semantic_metric_contract
(
    metric_id             varchar(64)                              not null
        primary key,
    metric_name           varchar(128)                             not null,
    business_definition   text                                     null,
    formula               text                                     null,
    unit                  varchar(32)                              null,
    direction_higher      varchar(16) default 'better'             not null comment 'worse|better',
    aggregation_rule_json varchar(256)                             null comment '{"allow":["avg","trend"],"deny":["sum"]}',
    owner                 varchar(64)                              null,
    version               varchar(32) default 'v1'                 not null,
    status                varchar(16) default 'active'             not null,
    updated_at            datetime(3) default CURRENT_TIMESTAMP(3) not null on update CURRENT_TIMESTAMP(3)
)
    charset = utf8mb4;

create table session_state_list
(
    id         bigint auto_increment
        primary key,
    session_id varchar(255)                        not null,
    state_key  varchar(255)                        not null,
    item_order int                                 not null,
    item_json  mediumtext                          not null,
    list_hash  varchar(64)                         null,
    created_at timestamp default CURRENT_TIMESTAMP null,
    constraint uk_session_key_order
        unique (session_id, state_key, item_order)
)
    charset = utf8mb4;

create index idx_session_key
    on session_state_list (session_id, state_key);

create table skill_approval
(
    id               bigint auto_increment
        primary key,
    publish_id       bigint                              null,
    draft_id         bigint                              null,
    action           varchar(32)                         not null,
    operator         varchar(64)                         not null,
    comment          text                                null,
    version_snapshot int                                 not null,
    created_at       timestamp default CURRENT_TIMESTAMP not null
)
    charset = utf8mb4;

create index idx_draft
    on skill_approval (draft_id);

create index idx_operator
    on skill_approval (operator);

create index idx_publish
    on skill_approval (publish_id);

create table skill_candidate
(
    fingerprint   varchar(255)                          not null
        primary key,
    user_id       varchar(64)                           not null,
    hit_count     int         default 0                 not null,
    last_query    text                                  null,
    last_trace_id varchar(64)                           null,
    metric_tag    varchar(64)                           null,
    status        varchar(16) default 'pending'         not null,
    synth_skill   varchar(128)                          null,
    updated_at    timestamp   default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP
)
    charset = utf8mb4;

create index idx_hit_count
    on skill_candidate (hit_count desc);

create index idx_metric_tag
    on skill_candidate (metric_tag);

create index idx_user_status
    on skill_candidate (user_id, status);

create table skill_draft
(
    id              bigint auto_increment
        primary key,
    skill_id        bigint                                not null,
    name            varchar(128)                          null,
    description     text                                  null,
    content         text                                  null,
    category        varchar(64)                           null,
    tags            varchar(512)                          null,
    status          varchar(32) default 'PENDING'         not null,
    submitter       varchar(64)                           not null,
    approver        varchar(64)                           null,
    approve_comment text                                  null,
    submitted_at    timestamp   default CURRENT_TIMESTAMP not null,
    approved_at     timestamp                             null
)
    charset = utf8mb4;

create index idx_skill
    on skill_draft (skill_id);

create index idx_status
    on skill_draft (status);

create index idx_submitter
    on skill_draft (submitter);

create table skill_index
(
    name                      varchar(128)                           not null
        primary key,
    fingerprint               varchar(255)                           null comment 'PR3 L1 lookup key, NULL until then',
    description               text                                   null,
    embedding                 longtext                               null comment 'PR3 reserved; JSON-encoded float[] for MySQL<8.4',
    version                   int         default 1                  not null,
    usage_count               int         default 0                  not null,
    success_count             int         default 0                  not null,
    failure_count             int         default 0                  not null,
    last_used                 timestamp                              null,
    evolving                  tinyint(1)  default 0                  not null comment 'PR4 cross-JVM evolve lock',
    status                    varchar(16) default 'active'           not null,
    tool_sequence_fingerprint varchar(255)                           null comment 'Phase 3 offline lookup key (tool-id sequence)',
    updated_at                timestamp   default CURRENT_TIMESTAMP  not null on update CURRENT_TIMESTAMP,
    source                    varchar(16) default 'auto_synthesized' not null comment 'skill origin: user_generated | auto_synthesized'
)
    charset = utf8mb4;

create index idx_source
    on skill_index (source);

create index idx_status
    on skill_index (status);

create index idx_tool_seq_fp
    on skill_index (tool_sequence_fingerprint);

create table skill_like
(
    id         bigint auto_increment
        primary key,
    skill_id   bigint                              not null,
    user_id    varchar(64)                         not null,
    created_at timestamp default CURRENT_TIMESTAMP not null,
    constraint uk_user_skill
        unique (user_id, skill_id)
)
    charset = utf8mb4;

create index idx_skill
    on skill_like (skill_id);

create index idx_user
    on skill_like (user_id);

create table skill_manage
(
    id            bigint auto_increment
        primary key,
    name          varchar(128)                          not null,
    description   text                                  null,
    content       text                                  null,
    category      varchar(64)                           null,
    tags          varchar(512)                          null,
    owner_user_id varchar(64)                           not null,
    status        varchar(32) default 'ACTIVE'          not null,
    like_count    bigint      default 0                 not null,
    created_at    timestamp   default CURRENT_TIMESTAMP not null,
    updated_at    timestamp   default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP,
    deleted_at    timestamp                             null,
    constraint uk_name
        unique (name)
)
    charset = utf8mb4;

create index idx_like_rank
    on skill_manage (like_count desc, updated_at desc);

create index idx_owner
    on skill_manage (owner_user_id);

create index idx_status
    on skill_manage (status);

create table skill_operation_history
(
    id          bigint auto_increment
        primary key,
    skill_id    bigint                              null,
    publish_id  bigint                              null,
    operator    varchar(64)                         not null,
    operation   varchar(64)                         not null,
    before_data text                                null,
    after_data  text                                null,
    created_at  timestamp default CURRENT_TIMESTAMP not null
)
    charset = utf8mb4;

create index idx_operator_time
    on skill_operation_history (operator, created_at);

create index idx_publish
    on skill_operation_history (publish_id);

create index idx_skill
    on skill_operation_history (skill_id);

create table skill_pending_judgement
(
    session_key       varchar(255)                        not null
        primary key,
    skills_json       text                                not null,
    exemplar_question varchar(1024)                       null,
    created_at        timestamp default CURRENT_TIMESTAMP null
)
    charset = utf8mb4;

create table skill_publish
(
    id                       bigint auto_increment
        primary key,
    skill_id                 bigint                                not null,
    target_type              varchar(32)                           not null,
    target_id                varchar(64)                           not null,
    target_name              varchar(128)                          not null,
    status                   varchar(32) default 'PENDING'         not null,
    submitter                varchar(64)                           not null,
    approver                 varchar(64)                           null,
    approve_time             timestamp                             null,
    current_approver_user_id varchar(64)                           null,
    last_approval_comment    text                                  null,
    last_approval_at         timestamp                             null,
    created_at               timestamp   default CURRENT_TIMESTAMP not null
)
    charset = utf8mb4;

create index idx_approver_pending
    on skill_publish (current_approver_user_id, status);

create index idx_skill
    on skill_publish (skill_id);

create index idx_status
    on skill_publish (status);

create index idx_submitter
    on skill_publish (submitter);

create table skill_reference
(
    id              bigint auto_increment
        primary key,
    source_skill_id bigint                              not null,
    target_skill_id bigint                              not null,
    creator         varchar(64)                         not null,
    created_at      timestamp default CURRENT_TIMESTAMP not null,
    constraint uk_source_target_creator
        unique (source_skill_id, target_skill_id, creator)
)
    charset = utf8mb4;

create index idx_creator
    on skill_reference (creator);

create index idx_target
    on skill_reference (target_skill_id);

create table skill_user_disable
(
    id         bigint auto_increment
        primary key,
    skill_id   bigint                              not null,
    user_id    varchar(64)                         not null,
    created_at timestamp default CURRENT_TIMESTAMP not null,
    constraint uk_user_skill
        unique (user_id, skill_id)
)
    charset = utf8mb4;

create table skill_version_history
(
    id          bigint auto_increment
        primary key,
    skill_id    bigint                              not null,
    version     int                                 not null,
    name        varchar(128)                        null,
    description text                                null,
    content     text                                null,
    category    varchar(64)                         null,
    tags        varchar(512)                        null,
    edited_by   varchar(64)                         not null,
    edit_reason varchar(256)                        null,
    created_at  timestamp default CURRENT_TIMESTAMP not null
)
    charset = utf8mb4;

create index idx_skill_version
    on skill_version_history (skill_id asc, version desc);

create table url_shortener
(
    id           bigint auto_increment
        primary key,
    short_code   varchar(16)                         not null comment 'Base62短码，如 aB3xK9mP2qR5tY8w',
    original_url text                                not null comment '原始完整URL',
    created_at   timestamp default CURRENT_TIMESTAMP not null comment '创建时间',
    expires_at   timestamp                           null comment '过期时间，NULL表示永不过期',
    constraint short_code
        unique (short_code)
)
    comment 'URL短链映射表';

create index idx_expires_at
    on url_shortener (expires_at);

create table user_model_config
(
    user_id     varchar(32)                        not null comment '用户ID'
        primary key,
    provider    varchar(32)                        not null comment '模型提供商（glm/openai/anthropic）',
    token       varchar(512)                       not null comment '用户的API Key',
    model_name  varchar(128)                       not null comment '模型名',
    request_url varchar(512)                       null comment '请求地址',
    created_at  datetime default CURRENT_TIMESTAMP null comment '创建时间',
    updated_at  datetime default CURRENT_TIMESTAMP null on update CURRENT_TIMESTAMP comment '更新时间'
)
    comment '用户模型配置表' charset = utf8mb4;

create table user_trace_summary
(
    id                  bigint auto_increment
        primary key,
    user_id             varchar(128)                            not null,
    date_key            varchar(16)                             not null,
    fingerprint         varchar(255)                            not null,
    runtime_fingerprint varchar(255)                            null comment 'metric fingerprint for L1 skill lookup',
    tool_sequence       text                                    not null,
    success_count       int           default 0                 not null,
    failure_count       int           default 0                 not null,
    failure_score       decimal(6, 1) default 0.0               not null,
    sample_query        text                                    null,
    user_query          text                                    null,
    tool_call_details   longtext                                null,
    status              varchar(16)   default 'pending'         null,
    created_at          timestamp     default CURRENT_TIMESTAMP null,
    constraint uk_user_date_fp
        unique (user_id, date_key, fingerprint)
)
    charset = utf8mb4;

create index idx_user_date
    on user_trace_summary (user_id, date_key);

create table verification_event
(
    id              bigint auto_increment
        primary key,
    event_id        varchar(64)  not null,
    session_id      varchar(128) not null,
    type            varchar(32)  not null comment 'AGENT_STARTED/TOOL_CALL_STARTED/.../ERROR_OCCURRED',
    actor           varchar(64)  not null,
    parent_event_id varchar(64)  null,
    payload_json    mediumtext   null,
    created_ts      bigint       not null comment 'HookEvent.getTimestamp()'
)
    charset = utf8mb4;

create index idx_ve_session
    on verification_event (session_id);

create index idx_ve_session_ts
    on verification_event (session_id, created_ts);

create table verification_feedback
(
    id          bigint auto_increment
        primary key,
    session_id  varchar(128)                             not null,
    verdict     varchar(16)                              not null comment 'pass/warn/fail/unverified (the verdict being labeled)',
    human_label varchar(16)                              not null comment 'correct/incorrect',
    note        varchar(512)                             null,
    created_by  varchar(64)                              null,
    created_at  datetime(3) default CURRENT_TIMESTAMP(3) not null
)
    charset = utf8mb4;

create index idx_vf_session
    on verification_feedback (session_id);

create index idx_vf_verdict
    on verification_feedback (verdict, human_label);

create table verification_record
(
    id                    bigint auto_increment
        primary key,
    session_id            varchar(128)                             not null,
    user_id               varchar(64)                              null,
    checkpoint            varchar(32)                              not null comment 'subagent-exit/supervisor-exit/per-critical-tool',
    trigger_level         varchar(8)                               null comment 'LOW/MEDIUM/HIGH',
    experiment_id         varchar(64)                              null comment 'A/B 实验ID(候选规则桶)',
    candidate_source      varchar(64)                              not null,
    candidate_conclusion  mediumtext                               null comment '候选结论文本,供 Replay 重放/重新校验',
    trust_score           int                                      null,
    verdict               varchar(16)                              not null comment 'pass/warn/fail/unverified',
    dim_tool              int                                      null,
    dim_data              int                                      null,
    dim_semantic          int                                      null,
    dim_adversarial       int                                      null,
    dim_evidence          int                                      null,
    dim_freshness         int                                      null,
    repair_type           varchar(32)                              null,
    summary               text                                     null,
    issues_json           text                                     null,
    corrections_json      text                                     null,
    loop_index            int         default 0                    null,
    model_name            varchar(64)                              null,
    latency_ms            bigint                                   null,
    version_snapshot_json text                                     null,
    created_at            datetime(3) default CURRENT_TIMESTAMP(3) not null
)
    charset = utf8mb4;

create index idx_vr_repair
    on verification_record (repair_type);

create index idx_vr_session
    on verification_record (session_id);

create index idx_vr_trigger
    on verification_record (trigger_level);

create index idx_vr_verdict_created
    on verification_record (verdict, created_at);

