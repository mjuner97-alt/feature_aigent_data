-- ============================================================================
-- Skill 管理全部数据库表 DDL + 模拟数据 (openGauss / GaussDB 版)
-- 用途: 本地测试时一键重建所有 skill 相关表 + 填充模拟数据
-- 数据库: openGauss (PostgreSQL 兼容, GaussDB)
-- 执行顺序: 先 DROP -> 再 CREATE -> 建触发器 -> 最后 INSERT
-- 生成日期: 2026-07-29
-- 源文件: docs/skill-management-all-in-one.sql (MySQL 版)
-- 清理说明: 移除了 PR3/PR4 预留字段 (embedding, evolving, tool_sequence_fingerprint)
--
-- MySQL -> openGauss 主要转换点:
--   1. AUTO_INCREMENT BIGINT 主键        -> BIGSERIAL PRIMARY KEY
--   2. ENGINE=InnoDB / DEFAULT CHARSET   -> 移除 (openGauss 在库/表空间级设置)
--   3. CURRENT_TIMESTAMP                 -> now()
--   4. ON UPDATE CURRENT_TIMESTAMP       -> 触发器函数 set_updated_at() + 每表 BEFORE UPDATE 触发器
--      (复刻 MySQL 语义: UPDATE 时未显式赋值则自动刷新 updated_at; 显式赋值则保留)
--      覆盖 skill_index / skill_candidate / skill_manage 三张含 updated_at 的表
--   5. 行内 KEY/UNIQUE KEY 索引          -> 独立 CREATE [UNIQUE] INDEX
--   6. 行内 COMMENT '...'                -> COMMENT ON COLUMN / COMMENT ON TABLE
--   7. 模拟数据中 content 的 '\n'        -> 改用 E'...' 转义字符串, 使其成为真实换行
--      (openGauss 默认 standard_conforming_strings=on, 普通 '...\n...' 是字面量 "反斜杠+n",
--       与 MySQL 行为不同; E'...' 才会把 \n 解释为换行)
--
-- 统计: 11 张表, 100 个字段, 1 个触发器函数(set_updated_at) + 3 个 BEFORE UPDATE 触发器
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

-- 2.1 skill_index -- Skill 检索注册表 (12 字段)
CREATE TABLE IF NOT EXISTS skill_index (
  name VARCHAR(128) PRIMARY KEY,
  fingerprint VARCHAR(255) NULL,
  description TEXT,
  version INT NOT NULL DEFAULT 1,
  usage_count INT NOT NULL DEFAULT 0,
  success_count INT NOT NULL DEFAULT 0,
  failure_count INT NOT NULL DEFAULT 0,
  last_used TIMESTAMP NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'active',
  source VARCHAR(16) NOT NULL DEFAULT 'auto_synthesized',
  owner_user_id VARCHAR(64) DEFAULT NULL,
  updated_at TIMESTAMP NOT NULL DEFAULT now()
);

COMMENT ON TABLE skill_index IS 'Skill 检索注册表';
COMMENT ON COLUMN skill_index.fingerprint IS 'L1 lookup key';
COMMENT ON COLUMN skill_index.source IS 'skill origin: user_generated | auto_synthesized';
COMMENT ON COLUMN skill_index.owner_user_id IS 'skill owner for isolation; NULL = global (auto_synthesized or legacy)';

DROP INDEX IF EXISTS idx_status ON skill_index;
CREATE INDEX idx_status ON skill_index(status);
DROP INDEX IF EXISTS idx_source ON skill_index;
CREATE INDEX idx_source ON skill_index(source);
DROP INDEX IF EXISTS idx_owner_user_id ON skill_index;
CREATE INDEX idx_owner_user_id ON skill_index(owner_user_id);

-- 2.2 skill_candidate -- 待蒸馏的 Skill 指纹暂存区 (9 字段)
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

COMMENT ON TABLE skill_candidate IS '待蒸馏的 Skill 指纹暂存区';

DROP INDEX IF EXISTS idx_user_status ON skill_candidate;
CREATE INDEX idx_user_status ON skill_candidate(user_id, status);
DROP INDEX IF EXISTS idx_hit_count ON skill_candidate;
CREATE INDEX idx_hit_count ON skill_candidate(hit_count DESC);
DROP INDEX IF EXISTS idx_metric_tag ON skill_candidate;
CREATE INDEX idx_metric_tag ON skill_candidate(metric_tag);

-- 2.3 skill_manage -- Skill 主表 (13 字段)
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

COMMENT ON TABLE skill_manage IS 'Skill 主表';
COMMENT ON COLUMN skill_manage.retrieval_name IS '映射到 skill_index.name 的检索名，page_<id> 格式';

DROP INDEX IF EXISTS uk_name ON skill_manage;
CREATE UNIQUE INDEX uk_name ON skill_manage(name);
DROP INDEX IF EXISTS idx_owner ON skill_manage;
CREATE INDEX idx_owner ON skill_manage(owner_user_id);
DROP INDEX IF EXISTS idx_status ON skill_manage;
CREATE INDEX idx_status ON skill_manage(status);
DROP INDEX IF EXISTS idx_like_rank ON skill_manage;
CREATE INDEX idx_like_rank ON skill_manage(like_count DESC, updated_at DESC);
DROP INDEX IF EXISTS idx_retrieval_name ON skill_manage;
CREATE INDEX idx_retrieval_name ON skill_manage(retrieval_name);

-- 2.4 skill_like -- 点赞记录表 (4 字段)
CREATE TABLE IF NOT EXISTS skill_like (
  id BIGSERIAL PRIMARY KEY,
  skill_id BIGINT NOT NULL,
  user_id VARCHAR(64) NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT now()
);

COMMENT ON TABLE skill_like IS '点赞记录表';

DROP INDEX IF EXISTS uk_user_skill ON skill_like;
CREATE UNIQUE INDEX uk_user_skill ON skill_like(user_id, skill_id);
DROP INDEX IF EXISTS idx_skill ON skill_like;
CREATE INDEX idx_skill ON skill_like(skill_id);
DROP INDEX IF EXISTS idx_user ON skill_like;
CREATE INDEX idx_user ON skill_like(user_id);

-- 2.5 skill_reference -- 引用关系表 (5 字段)
CREATE TABLE IF NOT EXISTS skill_reference (
  id BIGSERIAL PRIMARY KEY,
  source_skill_id BIGINT NOT NULL,
  target_skill_id BIGINT NOT NULL,
  creator VARCHAR(64) NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT now()
);

COMMENT ON TABLE skill_reference IS '引用关系表';

DROP INDEX IF EXISTS uk_source_target_creator ON skill_reference;
CREATE UNIQUE INDEX uk_source_target_creator ON skill_reference(source_skill_id, target_skill_id, creator);
DROP INDEX IF EXISTS idx_creator ON skill_reference;
CREATE INDEX idx_creator ON skill_reference(creator);
DROP INDEX IF EXISTS idx_target ON skill_reference;
CREATE INDEX idx_target ON skill_reference(target_skill_id);

-- 2.6 skill_publish -- Skill 发布表 (13 字段)
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

COMMENT ON TABLE skill_publish IS 'Skill 发布表';

DROP INDEX IF EXISTS idx_skill ON skill_publish;
CREATE INDEX idx_skill ON skill_publish(skill_id);
DROP INDEX IF EXISTS idx_status ON skill_publish;
CREATE INDEX idx_status ON skill_publish(status);
DROP INDEX IF EXISTS idx_submitter ON skill_publish;
CREATE INDEX idx_submitter ON skill_publish(submitter);
DROP INDEX IF EXISTS idx_approver_pending ON skill_publish;
CREATE INDEX idx_approver_pending ON skill_publish(current_approver_user_id, status);

-- 2.7 skill_approval -- 审批操作记录表 (8 字段)
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

DROP INDEX IF EXISTS idx_publish ON skill_approval;
CREATE INDEX idx_publish ON skill_approval(publish_id);
DROP INDEX IF EXISTS idx_draft ON skill_approval;
CREATE INDEX idx_draft ON skill_approval(draft_id);
DROP INDEX IF EXISTS idx_operator ON skill_approval;
CREATE INDEX idx_operator ON skill_approval(operator);

-- 2.8 skill_draft -- Skill 草稿表 (13 字段)
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

COMMENT ON TABLE skill_draft IS 'Skill 草稿表';

DROP INDEX IF EXISTS idx_skill ON skill_draft;
CREATE INDEX idx_skill ON skill_draft(skill_id);
DROP INDEX IF EXISTS idx_status ON skill_draft;
CREATE INDEX idx_status ON skill_draft(status);
DROP INDEX IF EXISTS idx_submitter ON skill_draft;
CREATE INDEX idx_submitter ON skill_draft(submitter);

-- 2.9 skill_version_history -- 版本历史表 (11 字段)
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

DROP INDEX IF EXISTS idx_skill_version ON skill_version_history;
CREATE INDEX idx_skill_version ON skill_version_history(skill_id, version DESC);

-- 2.10 skill_operation_history -- 操作历史表 (8 字段)
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

DROP INDEX IF EXISTS idx_skill ON skill_operation_history;
CREATE INDEX idx_skill ON skill_operation_history(skill_id);
DROP INDEX IF EXISTS idx_publish ON skill_operation_history;
CREATE INDEX idx_publish ON skill_operation_history(publish_id);
DROP INDEX IF EXISTS idx_operator_time ON skill_operation_history;
CREATE INDEX idx_operator_time ON skill_operation_history(operator, created_at);

-- 2.11 skill_user_disable -- 用户禁用 Skill 表 (4 字段)
CREATE TABLE IF NOT EXISTS skill_user_disable (
  id          BIGSERIAL PRIMARY KEY,
  skill_id    BIGINT NOT NULL,
  user_id     VARCHAR(64) NOT NULL,
  created_at  TIMESTAMP NOT NULL DEFAULT now()
);

COMMENT ON TABLE skill_user_disable IS '用户禁用 Skill 表';

DROP INDEX IF EXISTS uk_user_skill ON skill_user_disable;
CREATE UNIQUE INDEX uk_user_skill ON skill_user_disable(user_id, skill_id);

-- ============================================================================
-- 2.12 updated_at 自动刷新触发器(复刻 MySQL 的 ON UPDATE CURRENT_TIMESTAMP)
--     MySQL 语义: UPDATE 时若未显式给 updated_at 赋值则自动刷新为当前时间;
--     显式赋值则保留显式值。下方条件判断实现同等语义。
--     仅 skill_index / skill_candidate / skill_manage 三张表含 updated_at。
-- ============================================================================

CREATE OR REPLACE FUNCTION set_updated_at() RETURNS TRIGGER AS $$
BEGIN
  IF NEW.updated_at = OLD.updated_at THEN
    NEW.updated_at := now();
  END IF;
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_skill_index_updated_at ON skill_index;
CREATE TRIGGER trg_skill_index_updated_at
  BEFORE UPDATE ON skill_index
  FOR EACH ROW
  EXECUTE PROCEDURE set_updated_at();

DROP TRIGGER IF EXISTS trg_skill_candidate_updated_at ON skill_candidate;
CREATE TRIGGER trg_skill_candidate_updated_at
  BEFORE UPDATE ON skill_candidate
  FOR EACH ROW
  EXECUTE PROCEDURE set_updated_at();

DROP TRIGGER IF EXISTS trg_skill_manage_updated_at ON skill_manage;
CREATE TRIGGER trg_skill_manage_updated_at
  BEFORE UPDATE ON skill_manage
  FOR EACH ROW
  EXECUTE PROCEDURE set_updated_at();

-- ============================================================================
-- 3. INSERT 模拟数据
--    注意: 含换行的 content 字段使用 E'...' 转义字符串, \n 为真实换行(与 MySQL 一致)
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
(1, '缺陷密度分析技能', '分析缺陷密度趋势并给出优化建议', E'## 缺陷密度分析\n\n1. 获取缺陷数据\n2. 计算密度趋势\n3. 给出优化建议', '质量', 'defect,quality,analysis', 'user_001', 'ACTIVE', 5, 'page_1'),
(2, '查询优化技能', '优化SQL查询性能', E'## 查询优化\n\n1. 分析慢SQL\n2. 检查索引\n3. 优化建议', '性能', 'sql,query,optimization', 'user_001', 'ACTIVE', 3, 'page_2'),
(3, 'Agent创建的错误率分析', 'Agent自动创建的错误率诊断技能', E'## 错误率分析\n\n1. 检查error_rate指标\n2. 定位异常服务\n3. 给出修复建议', '质量', 'error_rate,diagnosis', 'user_001', 'ACTIVE', 0, 'usr_user_001_agent_skill');

-- 用户 B (user_002) 的 skill
INSERT INTO skill_manage (id, name, description, content, category, tags, owner_user_id, status, like_count, retrieval_name) VALUES
(4, '监控分析技能', '分析系统监控指标', E'## 监控分析\n\n1. 采集监控数据\n2. 识别异常\n3. 告警建议', '运维', 'monitor,alert,ops', 'user_002', 'ACTIVE', 2, 'page_3');

-- 已删除的 skill(deleted_at 非空)
INSERT INTO skill_manage (id, name, description, content, category, tags, owner_user_id, status, like_count, retrieval_name, deleted_at) VALUES
(5, '已废弃的技能', '这个技能已被删除', '已废弃内容', '其他', 'deprecated', 'user_001', 'INACTIVE', 0, 'page_5', '2026-07-20 10:00:00');

-- 推进 BIGSERIAL 序列: 上面显式插入了 id=1..5, 需同步序列, 否则后续不带 id 的 INSERT 会从 1 开始造成主键冲突
-- (MySQL 的 AUTO_INCREMENT 会自动跳过已插入值, openGauss 不会, 需手动 setval)
SELECT setval('skill_manage_id_seq', (SELECT MAX(id) FROM skill_manage));

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
(1, '缺陷密度分析技能(修订)', '增加覆盖率维度分析', E'## 缺陷密度分析(修订)\n\n1. 获取缺陷数据\n2. 计算密度趋势\n3. 分析覆盖率\n4. 给出优化建议', '质量', 'defect,quality,coverage', 'PENDING', 'user_001', NULL),
(2, '查询优化技能(v2)', '增加索引优化建议', E'## 查询优化 v2\n\n1. 分析慢SQL\n2. 检查索引\n3. 执行计划分析\n4. 优化建议', '性能', 'sql,query,index', 'APPROVED', 'user_001', 'admin_001');

-- 3.9 skill_version_history -- 版本历史
INSERT INTO skill_version_history (skill_id, version, name, description, content, category, tags, edited_by, edit_reason) VALUES
(1, 1, '缺陷密度分析技能', '初版', E'## 缺陷密度分析\n\n1. 获取缺陷数据\n2. 计算密度', '质量', 'defect', 'user_001', '初始创建'),
(1, 2, '缺陷密度分析技能', '增加优化建议', E'## 缺陷密度分析\n\n1. 获取缺陷数据\n2. 计算密度趋势\n3. 给出优化建议', '质量', 'defect,quality', 'user_001', '补充优化建议'),
(1, 3, '缺陷密度分析技能', '完善描述', E'## 缺陷密度分析\n\n1. 获取缺陷数据\n2. 计算密度趋势\n3. 给出优化建议', '质量', 'defect,quality,analysis', 'user_001', '完善tags和描述'),
(2, 1, '查询优化技能', '初版', E'## 查询优化\n\n1. 分析慢SQL\n2. 优化建议', '性能', 'sql', 'user_001', '初始创建'),
(2, 2, '查询优化技能', '增加索引检查', E'## 查询优化\n\n1. 分析慢SQL\n2. 检查索引\n3. 优化建议', '性能', 'sql,query', 'user_001', '增加索引检查步骤');

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

-- 验证 updated_at 触发器: 执行下面 UPDATE 后, updated_at 应被自动刷新(无需显式赋值)
-- UPDATE skill_manage SET status = status WHERE id = 1;
-- SELECT id, updated_at FROM skill_manage WHERE id = 1;
