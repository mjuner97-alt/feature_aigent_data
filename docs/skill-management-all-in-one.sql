-- ============================================================================
-- Skill 管理全部数据库表 DDL + 模拟数据
-- 用途: 本地测试时一键重建所有 skill 相关表 + 填充模拟数据
-- 数据库: MySQL (agentscope)
-- 执行顺序: 先 DROP -> 再 CREATE -> 最后 INSERT
-- 生成日期: 2026-07-29
-- 清理说明: 移除了 PR3/PR4 预留字段 (embedding, evolving, tool_sequence_fingerprint)
-- ============================================================================

-- ============================================================================
-- 1. DROP 已存在的表(按依赖倒序删除)
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
-- 2. CREATE TABLE
-- ============================================================================

-- 2.1 skill_index -- Skill 检索注册表
CREATE TABLE IF NOT EXISTS skill_index (
  name VARCHAR(128) PRIMARY KEY,
  fingerprint VARCHAR(255) NULL COMMENT 'L1 lookup key',
  description TEXT,
  version INT NOT NULL DEFAULT 1,
  usage_count INT NOT NULL DEFAULT 0,
  success_count INT NOT NULL DEFAULT 0,
  failure_count INT NOT NULL DEFAULT 0,
  last_used TIMESTAMP NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'active',
  source VARCHAR(16) NOT NULL DEFAULT 'auto_synthesized'
    COMMENT 'skill origin: user_generated | auto_synthesized',
  owner_user_id VARCHAR(64) DEFAULT NULL
    COMMENT 'skill owner for isolation; NULL = global (auto_synthesized or legacy)',
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  KEY idx_status (status),
  KEY idx_source (source),
  KEY idx_owner_user_id (owner_user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 2.2 skill_candidate -- 待蒸馏的 Skill 指纹暂存区
CREATE TABLE IF NOT EXISTS skill_candidate (
  fingerprint VARCHAR(255) PRIMARY KEY,
  user_id VARCHAR(64) NOT NULL,
  hit_count INT NOT NULL DEFAULT 0,
  last_query TEXT,
  last_trace_id VARCHAR(64) NULL,
  metric_tag VARCHAR(64) DEFAULT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'pending',
  synth_skill VARCHAR(128) NULL,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  KEY idx_user_status (user_id, status),
  KEY idx_hit_count (hit_count DESC),
  KEY idx_metric_tag (metric_tag)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 2.3 skill_manage -- Skill 主表
CREATE TABLE IF NOT EXISTS skill_manage (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  name VARCHAR(128) NOT NULL,
  description TEXT NULL,
  content TEXT NULL,
  category VARCHAR(64) NULL,
  tags VARCHAR(512) NULL,
  owner_user_id VARCHAR(64) NOT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
  like_count BIGINT NOT NULL DEFAULT 0,
  retrieval_name VARCHAR(128) NULL
    COMMENT '映射到 skill_index.name 的检索名，page_<id> 格式',
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted_at TIMESTAMP NULL DEFAULT NULL,
  UNIQUE KEY uk_name (name),
  KEY idx_owner (owner_user_id),
  KEY idx_status (status),
  KEY idx_like_rank (like_count DESC, updated_at DESC),
  KEY idx_retrieval_name (retrieval_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 2.4 skill_like -- 点赞记录表
CREATE TABLE IF NOT EXISTS skill_like (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  skill_id BIGINT NOT NULL,
  user_id VARCHAR(64) NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_user_skill (user_id, skill_id),
  KEY idx_skill (skill_id),
  KEY idx_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 2.5 skill_reference -- 引用关系表
CREATE TABLE IF NOT EXISTS skill_reference (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  source_skill_id BIGINT NOT NULL,
  target_skill_id BIGINT NOT NULL,
  creator VARCHAR(64) NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_source_target_creator (source_skill_id, target_skill_id, creator),
  KEY idx_creator (creator),
  KEY idx_target (target_skill_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 2.6 skill_publish -- Skill 发布表
CREATE TABLE IF NOT EXISTS skill_publish (
  id                       BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
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
  created_at               TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_skill (skill_id),
  KEY idx_status (status),
  KEY idx_submitter (submitter),
  KEY idx_approver_pending (current_approver_user_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 2.7 skill_approval -- 审批操作记录表
CREATE TABLE IF NOT EXISTS skill_approval (
  id               BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  publish_id       BIGINT NULL,
  draft_id         BIGINT NULL,
  action           VARCHAR(32) NOT NULL,
  operator         VARCHAR(64) NOT NULL,
  comment          TEXT,
  version_snapshot INT NOT NULL,
  created_at       TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_publish (publish_id),
  KEY idx_draft (draft_id),
  KEY idx_operator (operator)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 2.8 skill_draft -- Skill 草稿表
CREATE TABLE IF NOT EXISTS skill_draft (
  id              BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
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
  submitted_at    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  approved_at     TIMESTAMP NULL,
  KEY idx_skill (skill_id),
  KEY idx_status (status),
  KEY idx_submitter (submitter)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 2.9 skill_version_history -- 版本历史表
CREATE TABLE IF NOT EXISTS skill_version_history (
  id          BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  skill_id    BIGINT NOT NULL,
  version     INT NOT NULL,
  name        VARCHAR(128),
  description TEXT,
  content     TEXT,
  category    VARCHAR(64),
  tags        VARCHAR(512),
  edited_by   VARCHAR(64) NOT NULL,
  edit_reason VARCHAR(256),
  created_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_skill_version (skill_id, version DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 2.10 skill_operation_history -- 操作历史表
CREATE TABLE IF NOT EXISTS skill_operation_history (
  id          BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  skill_id    BIGINT,
  publish_id  BIGINT,
  operator    VARCHAR(64) NOT NULL,
  operation   VARCHAR(64) NOT NULL,
  before_data TEXT,
  after_data  TEXT,
  created_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_skill (skill_id),
  KEY idx_publish (publish_id),
  KEY idx_operator_time (operator, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 2.11 skill_user_disable -- 用户禁用 Skill 表
CREATE TABLE IF NOT EXISTS skill_user_disable (
  id          BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  skill_id    BIGINT NOT NULL,
  user_id     VARCHAR(64) NOT NULL,
  created_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_user_skill (user_id, skill_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ============================================================================
-- 3. INSERT 模拟数据
-- ============================================================================

-- 3.1 skill_index -- 检索注册表
-- auto_synthesized skill(全局共享, owner_user_id=NULL)
INSERT INTO skill_index (name, fingerprint, description, version, usage_count, success_count, failure_count, status, source, owner_user_id) VALUES
('defect_density_analysis', 'fp_defect_density_v1', '分析缺陷密度趋势并给出优化建议', 3, 15, 13, 2, 'active', 'auto_synthesized', NULL),
('response_time_analysis', 'fp_response_time_v1', '分析接口响应时间分布与瓶颈', 2, 10, 8, 2, 'active', 'auto_synthesized', NULL),
('error_rate_diagnosis', 'fp_error_rate_v1', '诊断错误率异常根因', 1, 5, 4, 1, 'active', 'auto_synthesized', NULL);

-- user_generated skill(用户隔离, owner_user_id 非 NULL)
-- 用户 A (user_001) 的 skill
INSERT INTO skill_index (name, fingerprint, description, version, usage_count, success_count, failure_count, status, source, owner_user_id) VALUES
('page_1', 'fp_page_1_defect', '用户A的缺陷分析专属技能', 1, 3, 2, 1, 'active', 'user_generated', 'user_001'),
('page_2', 'fp_page_2_query', '用户A的查询优化技能', 1, 2, 2, 0, 'active', 'user_generated', 'user_001'),
('usr_user_001_agent_skill', 'fp_usr_agent_001', 'Agent帮用户A创建的错误率分析技能', 1, 1, 1, 0, 'active', 'user_generated', 'user_001');

-- 用户 B (user_002) 的 skill
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

-- 3.3 skill_manage -- Skill 主表
-- 用户 A (user_001) 的 skill
INSERT INTO skill_manage (id, name, description, content, category, tags, owner_user_id, status, like_count, retrieval_name) VALUES
(1, '缺陷密度分析技能', '分析缺陷密度趋势并给出优化建议', '## 缺陷密度分析\n\n1. 获取缺陷数据\n2. 计算密度趋势\n3. 给出优化建议', '质量', 'defect,quality,analysis', 'user_001', 'ACTIVE', 5, 'page_1'),
(2, '查询优化技能', '优化SQL查询性能', '## 查询优化\n\n1. 分析慢SQL\n2. 检查索引\n3. 优化建议', '性能', 'sql,query,optimization', 'user_001', 'ACTIVE', 3, 'page_2'),
(3, 'Agent创建的错误率分析', 'Agent自动创建的错误率诊断技能', '## 错误率分析\n\n1. 检查error_rate指标\n2. 定位异常服务\n3. 给出修复建议', '质量', 'error_rate,diagnosis', 'user_001', 'ACTIVE', 0, 'usr_user_001_agent_skill');

-- 用户 B (user_002) 的 skill
INSERT INTO skill_manage (id, name, description, content, category, tags, owner_user_id, status, like_count, retrieval_name) VALUES
(4, '监控分析技能', '分析系统监控指标', '## 监控分析\n\n1. 采集监控数据\n2. 识别异常\n3. 告警建议', '运维', 'monitor,alert,ops', 'user_002', 'ACTIVE', 2, 'page_3');

-- 已删除的 skill(deleted_at 非空)
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

-- ============================================================================
-- 4. 验证查询(执行完上面后运行这些验证数据)
-- ============================================================================

-- 验证 skill_index 隔离
SELECT name, source, owner_user_id, status FROM skill_index ORDER BY source, owner_user_id;

-- 验证 skill_manage
SELECT id, name, owner_user_id, status, like_count, retrieval_name FROM skill_manage WHERE deleted_at IS NULL ORDER BY id;

-- 验证用户隔离: user_001 能检索到的 skill
SELECT name, source, owner_user_id FROM skill_index
  WHERE status = 'active'
  AND (owner_user_id = 'user_001' OR owner_user_id IS NULL)
  ORDER BY source;

-- 验证用户隔离: user_002 能检索到的 skill
SELECT name, source, owner_user_id FROM skill_index
  WHERE status = 'active'
  AND (owner_user_id = 'user_002' OR owner_user_id IS NULL)
  ORDER BY source;

-- 验证引用关系
SELECT sr.creator, sm.id AS skill_id, sm.name, sm.owner_user_id
  FROM skill_reference sr
  JOIN skill_manage sm ON sr.target_skill_id = sm.id
  ORDER BY sr.creator;

-- 验证点赞
SELECT sl.skill_id, sm.name, sl.user_id, sl.created_at
  FROM skill_like sl
  JOIN skill_manage sm ON sl.skill_id = sm.id
  ORDER BY sl.skill_id, sl.user_id;
