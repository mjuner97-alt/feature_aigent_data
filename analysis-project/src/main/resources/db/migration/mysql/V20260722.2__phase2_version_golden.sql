-- ============================================================================
-- V3.0 Phase 2: Version Registry + Golden Evaluation Pipeline (Flyway migration)
-- ----------------------------------------------------------------------------
-- Source: doc/featuredatanew/validation-agent-design-V3.0.md §14 / §23.5 / §23.6
--
-- Tables:
--   agent_version_registry   - P1: Agent/Prompt/Skill/SemanticContract/RepairPolicy versions
--   golden_dataset_case      - P0: golden question set (regression benchmark)
--   golden_evaluation_result - P0: per-case evaluation result (answer + verdict + accuracy)
-- ============================================================================

CREATE TABLE IF NOT EXISTS agent_version_registry (
  version_id VARCHAR(128) PRIMARY KEY,
  component VARCHAR(32) NOT NULL COMMENT 'AGENT/PROMPT/SKILL/SEMANTIC_CONTRACT/REPAIR_POLICY/TOOL',
  component_ref VARCHAR(256) NOT NULL,
  version VARCHAR(32) NOT NULL,
  checksum VARCHAR(64) DEFAULT NULL,
  released_by VARCHAR(64) DEFAULT NULL,
  released_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  golden_eval_id VARCHAR(64) DEFAULT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'candidate' COMMENT 'candidate/stable/deprecated',
  INDEX idx_avr_component (component, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS golden_dataset_case (
  case_id VARCHAR(64) PRIMARY KEY,
  question TEXT NOT NULL,
  category VARCHAR(64) DEFAULT NULL,
  expected_sql TEXT,
  expected_answer TEXT,
  expected_metric VARCHAR(64) DEFAULT NULL,
  difficulty VARCHAR(8) DEFAULT NULL COMMENT 'LOW/MEDIUM/HIGH',
  tags VARCHAR(256) DEFAULT NULL,
  version VARCHAR(32) NOT NULL DEFAULT 'v1'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS golden_evaluation_result (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  eval_id VARCHAR(64) NOT NULL,
  case_id VARCHAR(64) NOT NULL,
  agent_version VARCHAR(32) DEFAULT NULL,
  prompt_version VARCHAR(32) DEFAULT NULL,
  skill_version VARCHAR(32) DEFAULT NULL,
  semantic_version VARCHAR(32) DEFAULT NULL,
  actual_answer TEXT,
  trust_score INT DEFAULT NULL,
  verdict VARCHAR(16) DEFAULT NULL,
  accuracy_pass TINYINT DEFAULT 0,
  semantic_pass TINYINT DEFAULT 0,
  hallucination_flag TINYINT DEFAULT 0,
  repair_used VARCHAR(32) DEFAULT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  INDEX idx_ger_eval (eval_id),
  INDEX idx_ger_version (agent_version, prompt_version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ===== Seed: golden dataset (grounded in KnownEntities.DEPARTMENT_VERSION_QUALITY) =====
-- quality_score / defect_density: higher = worse. 一部23.1 二部13.1 三部3.1 四部6.1 五部26.1
INSERT INTO golden_dataset_case (case_id, question, category, expected_answer, expected_metric, difficulty, version) VALUES
  ('gq_001', '哪个部门缺陷密度最高？',             'single_query', '杭州开发五部', 'quality_score', 'LOW',    'v1'),
  ('gq_002', '哪个部门质量最好？',                 'single_query', '杭州开发三部', 'quality_score', 'LOW',    'v1'),
  ('gq_003', '杭州开发一部和二部哪个质量更差？',   'compare',      '杭州开发一部', 'quality_score', 'MEDIUM', 'v1'),
  ('gq_004', '杭州开发一部的缺陷密度是多少？',     'single_query', '23.1',         'quality_score', 'LOW',    'v1')
ON DUPLICATE KEY UPDATE expected_answer=VALUES(expected_answer), expected_metric=VALUES(expected_metric);
