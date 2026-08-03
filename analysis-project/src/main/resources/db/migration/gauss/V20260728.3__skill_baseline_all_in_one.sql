-- ============================================================================
-- Skill管理全部数据库表基线迁移(合并版)
-- 合并自原 V20260718.2 ~ V20260728.2 共 14 个迁移文件
-- 包含所有 skill 相关表的 DDL + 模拟数据
-- 目标数据库: openGauss(PostgreSQL 兼容)
-- ============================================================================

-- ============================================================================
-- 1. 删除已存在的表(保证幂等)
-- 1. 删除已存在的表(保证幂等)
-- ============================================================================

DROP TABLE IF EXISTS skill_user_disable;
DROP TABLE IF EXISTS skill_operation_history;
DROP TABLE IF EXISTS skill_version_history;
DROP TABLE IF EXISTS skill_draft;
DROP TABLE IF EXISTS skill_approval;
DROP TABLE IF EXISTS skill_publish;
DROP TABLE IF EXISTS skill_reference;
DROP TABLE IF EXISTS skill_like;
DROP TABLE IF EXISTS skill_manage;
DROP TABLE IF EXISTS skill_candidate;
DROP TABLE IF EXISTS skill_index;

-- ============================================================================
-- 2. 创建表
-- ============================================================================

-- 2.1 skill_index -- Skill检索注册表
CREATE TABLE IF NOT EXISTS skill_index (
  name VARCHAR(128) PRIMARY KEY,
  fingerprint VARCHAR(255) NULL,
  description TEXT,
  embedding TEXT NULL,
  version INT NOT NULL DEFAULT 1,
  usage_count INT NOT NULL DEFAULT 0,
  success_count INT NOT NULL DEFAULT 0,
  failure_count INT NOT NULL DEFAULT 0,
  last_used TIMESTAMP NULL,
  evolving BOOLEAN NOT NULL DEFAULT FALSE,
  status VARCHAR(16) NOT NULL DEFAULT 'active',
  source VARCHAR(16) NOT NULL DEFAULT 'auto_synthesized',
  owner_user_id VARCHAR(64) DEFAULT NULL,
  tool_sequence_fingerprint VARCHAR(255) DEFAULT NULL,
  updated_at TIMESTAMP NOT NULL DEFAULT now()
);

COMMENT ON TABLE skill_index IS 'Skill检索注册表';
COMMENT ON COLUMN skill_index.name IS 'Skill名称(主键)';
COMMENT ON COLUMN skill_index.fingerprint IS 'PR3 L1查找键,在生成前为NULL';
COMMENT ON COLUMN skill_index.description IS 'Skill描述';
COMMENT ON COLUMN skill_index.embedding IS 'PR3预留;JSON编码的float数组(MySQL<8.4兼容)';
COMMENT ON COLUMN skill_index.version IS '版本号';
COMMENT ON COLUMN skill_index.usage_count IS '使用次数';
COMMENT ON COLUMN skill_index.success_count IS '成功次数';
COMMENT ON COLUMN skill_index.failure_count IS '失败次数';
COMMENT ON COLUMN skill_index.last_used IS '最后使用时间';
COMMENT ON COLUMN skill_index.evolving IS 'PR4跨JVM演进锁';
COMMENT ON COLUMN skill_index.status IS '状态: active/blacklist';
COMMENT ON COLUMN skill_index.source IS '来源: user_generated(用户创建) | auto_synthesized(自动合成)';
COMMENT ON COLUMN skill_index.owner_user_id IS 'PR5: 用户隔离的skill所有者; NULL=全局(自动合成或遗留数据)';
COMMENT ON COLUMN skill_index.tool_sequence_fingerprint IS 'Phase 3离线查找键(工具ID序列)';
COMMENT ON COLUMN skill_index.updated_at IS '更新时间';

CREATE INDEX idx_status ON skill_index(status);
CREATE INDEX idx_source ON skill_index(source);
CREATE INDEX idx_tool_seq_fp ON skill_index(tool_sequence_fingerprint);
CREATE INDEX idx_owner_user_id ON skill_index(owner_user_id);

-- 2.2 skill_candidate -- 待蒸馏的Skill指纹暂存区
CREATE TABLE IF NOT EXISTS skill_candidate (
  fingerprint VARCHAR(255) PRIMARY KEY,
  user_id VARCHAR(64) NOT NULL,
  hit_count INT NOT NULL DEFAULT 0,
  last_query TEXT,
  last_trace_id VARCHAR(64) NULL,
  metric_tag VARCHAR(64) DEFAULT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'pending',
  synth_skill VARCHAR(128) NULL,
  updated_at TIMESTAMP NOT NULL DEFAULT now()
);

COMMENT ON TABLE skill_candidate IS '待蒸馏的Skill指纹暂存区';
COMMENT ON COLUMN skill_candidate.fingerprint IS '指纹(主键)';
COMMENT ON COLUMN skill_candidate.user_id IS '用户ID';
COMMENT ON COLUMN skill_candidate.hit_count IS '命中次数';
COMMENT ON COLUMN skill_candidate.last_query IS '最后查询内容';
COMMENT ON COLUMN skill_candidate.last_trace_id IS '最后追踪ID';
COMMENT ON COLUMN skill_candidate.metric_tag IS '指标标签';
COMMENT ON COLUMN skill_candidate.status IS '状态: pending/synthesized/rejected';
COMMENT ON COLUMN skill_candidate.synth_skill IS '合成后的skill名称';
COMMENT ON COLUMN skill_candidate.updated_at IS '更新时间';

CREATE INDEX idx_user_status ON skill_candidate(user_id, status);
CREATE INDEX idx_hit_count ON skill_candidate(hit_count DESC);
CREATE INDEX idx_metric_tag ON skill_candidate(metric_tag);

-- 2.3 skill_manage -- Skill主表
CREATE TABLE IF NOT EXISTS skill_manage (
  id BIGSERIAL PRIMARY KEY,
  name VARCHAR(128) NOT NULL,
  description TEXT NULL,
  content TEXT NULL,
  category VARCHAR(64) NULL,
  tags VARCHAR(512) NULL,
  owner_user_id VARCHAR(64) NOT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
  like_count BIGINT NOT NULL DEFAULT 0,
  retrieval_name VARCHAR(128) NULL,
  created_at TIMESTAMP NOT NULL DEFAULT now(),
  updated_at TIMESTAMP NOT NULL DEFAULT now(),
  deleted_at TIMESTAMP NULL DEFAULT NULL
);

COMMENT ON TABLE skill_manage IS 'Skill主表';
COMMENT ON COLUMN skill_manage.id IS '主键ID';
COMMENT ON COLUMN skill_manage.name IS 'Skill名称';
COMMENT ON COLUMN skill_manage.description IS 'Skill描述';
COMMENT ON COLUMN skill_manage.content IS 'Skill内容(Markdown格式)';
COMMENT ON COLUMN skill_manage.category IS '分类';
COMMENT ON COLUMN skill_manage.tags IS '标签(逗号分隔)';
COMMENT ON COLUMN skill_manage.owner_user_id IS '所有者用户ID';
COMMENT ON COLUMN skill_manage.status IS '状态: ACTIVE/INACTIVE';
COMMENT ON COLUMN skill_manage.like_count IS '点赞数';
COMMENT ON COLUMN skill_manage.retrieval_name IS '映射到skill_index.name的检索名';
COMMENT ON COLUMN skill_manage.created_at IS '创建时间';
COMMENT ON COLUMN skill_manage.updated_at IS '更新时间';
COMMENT ON COLUMN skill_manage.deleted_at IS '删除时间(软删除)';

-- uk_name 唯一约束已移除:软删除后 name 仍占位,导致无法创建同名 skill。
-- 名称冲突改由应用层 existsByName(SQL 已排除 DELETED) + SkillExceptionHandler(409) 处理。
-- 替换为普通索引:existsByName 等值查询仍走索引,但不强制唯一。
-- CREATE UNIQUE INDEX uk_name ON skill_manage(name);
CREATE INDEX idx_name ON skill_manage(name);
CREATE INDEX idx_owner ON skill_manage(owner_user_id);
CREATE INDEX idx_status ON skill_manage(status);
CREATE INDEX idx_like_rank ON skill_manage(like_count DESC, updated_at DESC);
CREATE INDEX idx_retrieval_name ON skill_manage(retrieval_name);

-- 2.4 skill_like -- 点赞记录表
CREATE TABLE IF NOT EXISTS skill_like (
  id BIGSERIAL PRIMARY KEY,
  skill_id BIGINT NOT NULL,
  user_id VARCHAR(64) NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT now()
);

COMMENT ON TABLE skill_like IS '点赞记录表';
COMMENT ON COLUMN skill_like.id IS '主键ID';
COMMENT ON COLUMN skill_like.skill_id IS 'Skill ID';
COMMENT ON COLUMN skill_like.user_id IS '用户ID';
COMMENT ON COLUMN skill_like.created_at IS '创建时间';

CREATE UNIQUE INDEX uk_user_skill ON skill_like(user_id, skill_id);
CREATE INDEX idx_skill ON skill_like(skill_id);
CREATE INDEX idx_user ON skill_like(user_id);

-- 2.5 skill_reference -- 引用关系表
CREATE TABLE IF NOT EXISTS skill_reference (
  id BIGSERIAL PRIMARY KEY,
  source_skill_id BIGINT NOT NULL,
  target_skill_id BIGINT NOT NULL,
  creator VARCHAR(64) NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT now()
);

COMMENT ON TABLE skill_reference IS '引用关系表';
COMMENT ON COLUMN skill_reference.id IS '主键ID';
COMMENT ON COLUMN skill_reference.source_skill_id IS '源Skill ID';
COMMENT ON COLUMN skill_reference.target_skill_id IS '目标Skill ID';
COMMENT ON COLUMN skill_reference.creator IS '创建者用户ID';
COMMENT ON COLUMN skill_reference.created_at IS '创建时间';

CREATE UNIQUE INDEX uk_source_target_creator ON skill_reference(source_skill_id, target_skill_id, creator);
CREATE INDEX idx_creator ON skill_reference(creator);
CREATE INDEX idx_target ON skill_reference(target_skill_id);

-- 2.6 skill_publish -- Skill发布表
CREATE TABLE IF NOT EXISTS skill_publish (
  id                       BIGSERIAL PRIMARY KEY,
  skill_id                 BIGINT NOT NULL,
  target_type              VARCHAR(32) NOT NULL,
  target_id                VARCHAR(64) NOT NULL,
  target_name              VARCHAR(128) NOT NULL,
  status                   VARCHAR(32) NOT NULL DEFAULT 'PENDING',
  submitter                VARCHAR(64) NOT NULL,
  approver                 VARCHAR(64),
  approve_time             TIMESTAMP NULL,
  current_approver_user_id VARCHAR(64),
  last_approval_comment    TEXT,
  last_approval_at         TIMESTAMP NULL,
  created_at               TIMESTAMP NOT NULL DEFAULT now()
);

COMMENT ON TABLE skill_publish IS 'Skill发布表';
COMMENT ON COLUMN skill_publish.id IS '主键ID';
COMMENT ON COLUMN skill_publish.skill_id IS 'Skill ID';
COMMENT ON COLUMN skill_publish.target_type IS '目标类型: TEAM/PROJECT';
COMMENT ON COLUMN skill_publish.target_id IS '目标ID';
COMMENT ON COLUMN skill_publish.target_name IS '目标名称';
COMMENT ON COLUMN skill_publish.status IS '状态: PENDING/APPROVED/REJECTED';
COMMENT ON COLUMN skill_publish.submitter IS '提交者';
COMMENT ON COLUMN skill_publish.approver IS '审批者';
COMMENT ON COLUMN skill_publish.approve_time IS '审批时间';
COMMENT ON COLUMN skill_publish.current_approver_user_id IS '当前审批者用户ID';
COMMENT ON COLUMN skill_publish.last_approval_comment IS '最后审批评论';
COMMENT ON COLUMN skill_publish.last_approval_at IS '最后审批时间';
COMMENT ON COLUMN skill_publish.created_at IS '创建时间';

CREATE INDEX idx_skill ON skill_publish(skill_id);
CREATE INDEX idx_status ON skill_publish(status);
CREATE INDEX idx_submitter ON skill_publish(submitter);
CREATE INDEX idx_approver_pending ON skill_publish(current_approver_user_id, status);

-- 2.7 skill_approval -- 审批操作记录表
CREATE TABLE IF NOT EXISTS skill_approval (
  id               BIGSERIAL PRIMARY KEY,
  publish_id       BIGINT NULL,
  draft_id         BIGINT NULL,
  action           VARCHAR(32) NOT NULL,
  operator         VARCHAR(64) NOT NULL,
  comment          TEXT,
  version_snapshot INT NOT NULL,
  created_at       TIMESTAMP NOT NULL DEFAULT now()
);

COMMENT ON TABLE skill_approval IS '审批操作记录表';
COMMENT ON COLUMN skill_approval.id IS '主键ID';
COMMENT ON COLUMN skill_approval.publish_id IS '发布记录ID';
COMMENT ON COLUMN skill_approval.draft_id IS '草稿ID';
COMMENT ON COLUMN skill_approval.action IS '操作: APPROVE/REJECT/SUBMIT';
COMMENT ON COLUMN skill_approval.operator IS '操作者';
COMMENT ON COLUMN skill_approval.comment IS '评论';
COMMENT ON COLUMN skill_approval.version_snapshot IS '版本快照';
COMMENT ON COLUMN skill_approval.created_at IS '创建时间';

CREATE INDEX idx_publish ON skill_approval(publish_id);
CREATE INDEX idx_draft ON skill_approval(draft_id);
CREATE INDEX idx_operator ON skill_approval(operator);

-- 2.8 skill_draft -- Skill草稿表
CREATE TABLE IF NOT EXISTS skill_draft (
  id              BIGSERIAL PRIMARY KEY,
  skill_id        BIGINT NOT NULL,
  name            VARCHAR(128),
  description     TEXT,
  content         TEXT,
  category        VARCHAR(64),
  tags            VARCHAR(512),
  status          VARCHAR(32) NOT NULL DEFAULT 'PENDING',
  submitter       VARCHAR(64) NOT NULL,
  approver        VARCHAR(64),
  approve_comment TEXT,
  submitted_at    TIMESTAMP NOT NULL DEFAULT now(),
  approved_at     TIMESTAMP NULL
);

COMMENT ON TABLE skill_draft IS 'Skill草稿表';
COMMENT ON COLUMN skill_draft.id IS '主键ID';
COMMENT ON COLUMN skill_draft.skill_id IS 'Skill ID';
COMMENT ON COLUMN skill_draft.name IS 'Skill名称';
COMMENT ON COLUMN skill_draft.description IS 'Skill描述';
COMMENT ON COLUMN skill_draft.content IS 'Skill内容';
COMMENT ON COLUMN skill_draft.category IS '分类';
COMMENT ON COLUMN skill_draft.tags IS '标签';
COMMENT ON COLUMN skill_draft.status IS '状态: PENDING/APPROVED/REJECTED';
COMMENT ON COLUMN skill_draft.submitter IS '提交者';
COMMENT ON COLUMN skill_draft.approver IS '审批者';
COMMENT ON COLUMN skill_draft.approve_comment IS '审批评论';
COMMENT ON COLUMN skill_draft.submitted_at IS '提交时间';
COMMENT ON COLUMN skill_draft.approved_at IS '审批时间';

CREATE INDEX idx_skill ON skill_draft(skill_id);
CREATE INDEX idx_status ON skill_draft(status);
CREATE INDEX idx_submitter ON skill_draft(submitter);

-- 2.9 skill_version_history -- 版本历史表
CREATE TABLE IF NOT EXISTS skill_version_history (
  id          BIGSERIAL PRIMARY KEY,
  skill_id    BIGINT NOT NULL,
  version     INT NOT NULL,
  name        VARCHAR(128),
  description TEXT,
  content     TEXT,
  category    VARCHAR(64),
  tags        VARCHAR(512),
  edited_by   VARCHAR(64) NOT NULL,
  edit_reason VARCHAR(256),
  created_at  TIMESTAMP NOT NULL DEFAULT now()
);

COMMENT ON TABLE skill_version_history IS '版本历史表';
COMMENT ON COLUMN skill_version_history.id IS '主键ID';
COMMENT ON COLUMN skill_version_history.skill_id IS 'Skill ID';
COMMENT ON COLUMN skill_version_history.version IS '版本号';
COMMENT ON COLUMN skill_version_history.name IS 'Skill名称';
COMMENT ON COLUMN skill_version_history.description IS 'Skill描述';
COMMENT ON COLUMN skill_version_history.content IS 'Skill内容';
COMMENT ON COLUMN skill_version_history.category IS '分类';
COMMENT ON COLUMN skill_version_history.tags IS '标签';
COMMENT ON COLUMN skill_version_history.edited_by IS '编辑者';
COMMENT ON COLUMN skill_version_history.edit_reason IS '编辑原因';
COMMENT ON COLUMN skill_version_history.created_at IS '创建时间';

CREATE INDEX idx_skill_version ON skill_version_history(skill_id, version DESC);

-- 2.10 skill_operation_history -- 操作历史表
CREATE TABLE IF NOT EXISTS skill_operation_history (
  id          BIGSERIAL PRIMARY KEY,
  skill_id    BIGINT,
  publish_id  BIGINT,
  operator    VARCHAR(64) NOT NULL,
  operation   VARCHAR(64) NOT NULL,
  before_data TEXT,
  after_data  TEXT,
  created_at  TIMESTAMP NOT NULL DEFAULT now()
);

COMMENT ON TABLE skill_operation_history IS '操作历史表';
COMMENT ON COLUMN skill_operation_history.id IS '主键ID';
COMMENT ON COLUMN skill_operation_history.skill_id IS 'Skill ID';
COMMENT ON COLUMN skill_operation_history.publish_id IS '发布记录ID';
COMMENT ON COLUMN skill_operation_history.operator IS '操作者';
COMMENT ON COLUMN skill_operation_history.operation IS '操作类型';
COMMENT ON COLUMN skill_operation_history.before_data IS '操作前数据(JSON)';
COMMENT ON COLUMN skill_operation_history.after_data IS '操作后数据(JSON)';
COMMENT ON COLUMN skill_operation_history.created_at IS '创建时间';

CREATE INDEX idx_skill ON skill_operation_history(skill_id);
CREATE INDEX idx_publish ON skill_operation_history(publish_id);
CREATE INDEX idx_operator_time ON skill_operation_history(operator, created_at);

-- 2.11 skill_user_disable -- 用户禁用Skill表
CREATE TABLE IF NOT EXISTS skill_user_disable (
  id          BIGSERIAL PRIMARY KEY,
  skill_id    BIGINT NOT NULL,
  user_id     VARCHAR(64) NOT NULL,
  created_at  TIMESTAMP NOT NULL DEFAULT now()
);

COMMENT ON TABLE skill_user_disable IS '用户禁用Skill表';
COMMENT ON COLUMN skill_user_disable.id IS '主键ID';
COMMENT ON COLUMN skill_user_disable.skill_id IS 'Skill ID';
COMMENT ON COLUMN skill_user_disable.user_id IS '用户ID';
COMMENT ON COLUMN skill_user_disable.created_at IS '创建时间';

CREATE UNIQUE INDEX uk_user_skill ON skill_user_disable(user_id, skill_id);

-- ============================================================================
-- 3. 插入模拟数据
-- ============================================================================

-- 3.1 skill_index -- 检索注册表
-- 自动合成skill(全局共享, owner_user_id=NULL)
INSERT INTO skill_index (name, fingerprint, description, version, usage_count, success_count, failure_count, status, source, owner_user_id, tool_sequence_fingerprint) VALUES
('defect_density_analysis', 'fp_defect_density_v1', '分析缺陷密度趋势并给出优化建议', 3, 15, 13, 2, 'active', 'auto_synthesized', NULL, 'ts_fp_defect_001'),
('response_time_analysis', 'fp_response_time_v1', '分析接口响应时间分布与瓶颈', 2, 10, 8, 2, 'active', 'auto_synthesized', NULL, 'ts_fp_response_001'),
('error_rate_diagnosis', 'fp_error_rate_v1', '诊断错误率异常根因', 1, 5, 4, 1, 'active', 'auto_synthesized', NULL, 'ts_fp_error_001');

-- 用户A (user_001) 的skill
INSERT INTO skill_index (name, fingerprint, description, version, usage_count, success_count, failure_count, status, source, owner_user_id) VALUES
('page_1', 'fp_page_1_defect', '用户A的缺陷分析专属技能', 1, 3, 2, 1, 'active', 'user_generated', 'user_001'),
('page_2', 'fp_page_2_query', '用户A的查询优化技能', 1, 2, 2, 0, 'active', 'user_generated', 'user_001'),
('usr_user_001_agent_skill', 'fp_usr_agent_001', 'Agent帮用户A创建的错误率分析技能', 1, 1, 1, 0, 'active', 'user_generated', 'user_001');

-- 用户B (user_002) 的skill
INSERT INTO skill_index (name, fingerprint, description, version, usage_count, success_count, failure_count, status, source, owner_user_id) VALUES
('page_3', 'fp_page_3_monitor', '用户B的监控分析技能', 1, 4, 3, 1, 'active', 'user_generated', 'user_002');

-- 引用副本(用户B引用了用户A的page_1)
INSERT INTO skill_index (name, fingerprint, description, version, usage_count, success_count, failure_count, status, source, owner_user_id) VALUES
('ref_page_1__u_user_002', 'fp_ref_page_1_u002', '用户B引用的缺陷分析技能副本', 1, 0, 0, 0, 'active', 'user_generated', 'user_002');

-- 3.2 skill_candidate -- 待蒸馏指纹
INSERT INTO skill_candidate (fingerprint, user_id, hit_count, last_query, last_trace_id, metric_tag, status, synth_skill) VALUES
('fp_candidate_001', 'user_001', 4, '如何分析代码覆盖率趋势', 'trace_001', 'coverage', 'pending', NULL),
('fp_candidate_002', 'user_001', 6, '分析测试通过率下降原因', 'trace_002', 'pass_rate', 'pending', NULL),
('fp_candidate_003', 'user_002', 3, '检查性能瓶颈指标', 'trace_003', 'performance', 'pending', NULL);

-- 3.3 skill_manage -- Skill主表
INSERT INTO skill_manage (id, name, description, content, category, tags, owner_user_id, status, like_count, retrieval_name) VALUES
(1, '缺陷密度分析技能', '分析缺陷密度趋势并给出优化建议', '## 缺陷密度分析\n\n1. 获取缺陷数据\n2. 计算密度趋势\n3. 给出优化建议', '质量', 'defect,quality,analysis', 'user_001', 'ACTIVE', 5, 'page_1'),
(2, '查询优化技能', '优化SQL查询性能', '## 查询优化\n\n1. 分析慢SQL\n2. 检查索引\n3. 优化建议', '性能', 'sql,query,optimization', 'user_001', 'ACTIVE', 3, 'page_2'),
(3, 'Agent创建的错误率分析', 'Agent自动创建的错误率诊断技能', '## 错误率分析\n\n1. 检查error_rate指标\n2. 定位异常服务\n3. 给出修复建议', '质量', 'error_rate,diagnosis', 'user_001', 'ACTIVE', 0, 'usr_user_001_agent_skill');

INSERT INTO skill_manage (id, name, description, content, category, tags, owner_user_id, status, like_count, retrieval_name) VALUES
(4, '监控分析技能', '分析系统监控指标', '## 监控分析\n\n1. 采集监控数据\n2. 识别异常\n3. 告警建议', '运维', 'monitor,alert,ops', 'user_002', 'ACTIVE', 2, 'page_3');

INSERT INTO skill_manage (id, name, description, content, category, tags, owner_user_id, status, like_count, retrieval_name, deleted_at) VALUES
(5, '已废弃的技能', '这个技能已被删除', '已废弃内容', '其他', 'deprecated', 'user_001', 'INACTIVE', 0, 'page_5', '2026-07-20 10:00:00');

-- 3.4 skill_like -- 点赞记录
INSERT INTO skill_like (skill_id, user_id) VALUES
(1, 'user_001'),
(1, 'user_002'),
(1, 'user_003'),
(2, 'user_001'),
(2, 'user_002'),
(4, 'user_002');

-- 3.5 skill_reference -- 引用关系
INSERT INTO skill_reference (source_skill_id, target_skill_id, creator) VALUES
(1, 1, 'user_002'),
(4, 4, 'user_002'),
(2, 2, 'user_001');

-- 3.6 skill_publish -- 发布记录
INSERT INTO skill_publish (skill_id, target_type, target_id, target_name, status, submitter, approver, approve_time, current_approver_user_id) VALUES
(1, 'TEAM', 'team_qa', 'QA团队', 'APPROVED', 'user_001', 'admin_001', '2026-07-25 14:00:00', NULL),
(2, 'TEAM', 'team_dev', '开发团队', 'PENDING', 'user_001', NULL, NULL, 'admin_001'),
(4, 'PROJECT', 'proj_platform', '平台项目', 'REJECTED', 'user_002', 'admin_001', '2026-07-26 10:00:00', NULL);

-- 3.7 skill_approval -- 审批操作记录
INSERT INTO skill_approval (publish_id, draft_id, action, operator, comment, version_snapshot) VALUES
(1, NULL, 'APPROVE', 'admin_001', '技能内容完善,同意发布', 1),
(3, NULL, 'REJECT', 'admin_001', '内容不够详细,请补充具体步骤', 1),
(2, NULL, 'SUBMIT', 'user_001', '提交审批', 1);

-- 3.8 skill_draft -- 草稿
INSERT INTO skill_draft (skill_id, name, description, content, category, tags, status, submitter, approver) VALUES
(1, '缺陷密度分析技能(修订)', '增加覆盖率维度分析', '## 缺陷密度分析(修订)\n\n1. 获取缺陷数据\n2. 计算密度趋势\n3. 分析覆盖率\n4. 给出优化建议', '质量', 'defect,quality,coverage', 'PENDING', 'user_001', NULL),
(2, '查询优化技能(v2)', '增加索引优化建议', '## 查询优化 v2\n\n1. 分析慢SQL\n2. 检查索引\n3. 执行计划分析\n4. 优化建议', '性能', 'sql,query,index', 'APPROVED', 'user_001', 'admin_001');

-- 3.9 skill_version_history -- 版本历史
INSERT INTO skill_version_history (skill_id, version, name, description, content, category, tags, edited_by, edit_reason) VALUES
(1, 1, '缺陷密度分析技能', '初版', '## 缺陷密度分析\n\n1. 获取缺陷数据\n2. 计算密度', '质量', 'defect', 'user_001', '初始创建'),
(1, 2, '缺陷密度分析技能', '增加优化建议', '## 缺陷密度分析\n\n1. 获取缺陷数据\n2. 计算密度趋势\n3. 给出优化建议', '质量', 'defect,quality', 'user_001', '补充优化建议'),
(1, 3, '缺陷密度分析技能', '完善描述', '## 缺陷密度分析\n\n1. 获取缺陷数据\n2. 计算密度趋势\n3. 给出优化建议', '质量', 'defect,quality,analysis', 'user_001', '完善tags和描述'),
(2, 1, '查询优化技能', '初版', '## 查询优化\n\n1. 分析慢SQL\n2. 优化建议', '性能', 'sql', 'user_001', '初始创建'),
(2, 2, '查询优化技能', '增加索引检查', '## 查询优化\n\n1. 分析慢SQL\n2. 检查索引\n3. 优化建议', '性能', 'sql,query', 'user_001', '增加索引检查步骤');

-- 3.10 skill_operation_history -- 操作历史
INSERT INTO skill_operation_history (skill_id, publish_id, operator, operation, before_data, after_data) VALUES
(1, NULL, 'user_001', 'CREATE', NULL, '{"name":"缺陷密度分析技能","status":"ACTIVE"}'),
(1, NULL, 'user_001', 'UPDATE', '{"version":1}', '{"version":2,"description":"增加优化建议"}'),
(1, 1, 'admin_001', 'PUBLISH_APPROVE', '{"status":"PENDING"}', '{"status":"APPROVED"}'),
(2, 2, 'user_001', 'PUBLISH_SUBMIT', NULL, '{"status":"PENDING","target":"team_dev"}'),
(4, 3, 'admin_001', 'PUBLISH_REJECT', '{"status":"PENDING"}', '{"status":"REJECTED"}');

-- 3.11 skill_user_disable -- 用户禁用
INSERT INTO skill_user_disable (skill_id, user_id) VALUES
(4, 'user_001');
