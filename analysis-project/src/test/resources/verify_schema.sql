-- H2 (MODE=MySQL) test schema for VerifyEndToEndIntegrationTest.
-- Mirrors the V3.0/V4.0 verification tables (Flyway V20260722.1-7) in H2-compatible form:
-- no FULLTEXT, no inline COMMENT, large text as CLOB, TINYINT (no length). Indexes omitted
-- (not needed for test correctness). UNIQUE kept only where ON DUPLICATE KEY UPDATE upserts need it.

CREATE TABLE IF NOT EXISTS verification_event (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  event_id VARCHAR(64) NOT NULL,
  session_id VARCHAR(128) NOT NULL,
  type VARCHAR(32) NOT NULL,
  actor VARCHAR(64) NOT NULL,
  parent_event_id VARCHAR(64),
  payload_json CLOB,
  created_ts BIGINT NOT NULL
);
CREATE TABLE IF NOT EXISTS verification_record (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  session_id VARCHAR(128) NOT NULL,
  user_id VARCHAR(64),
  checkpoint VARCHAR(32) NOT NULL,
  trigger_level VARCHAR(8),
  experiment_id VARCHAR(64),
  candidate_source VARCHAR(64) NOT NULL,
  candidate_conclusion CLOB,
  trust_score INT,
  verdict VARCHAR(16) NOT NULL,
  dim_tool INT, dim_data INT, dim_semantic INT, dim_adversarial INT, dim_evidence INT, dim_freshness INT,
  repair_type VARCHAR(32),
  summary CLOB,
  issues_json CLOB,
  corrections_json CLOB,
  loop_index INT,
  model_name VARCHAR(64),
  latency_ms BIGINT,
  created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3)
);
CREATE TABLE IF NOT EXISTS semantic_metric_contract (
  metric_id VARCHAR(64) PRIMARY KEY,
  metric_name VARCHAR(128) NOT NULL,
  business_definition CLOB,
  formula CLOB,
  unit VARCHAR(32),
  direction_higher VARCHAR(16) NOT NULL DEFAULT 'better',
  aggregation_rule_json VARCHAR(256),
  owner VARCHAR(64),
  version VARCHAR(32) NOT NULL DEFAULT 'v1',
  status VARCHAR(16) NOT NULL DEFAULT 'active'
);
CREATE TABLE IF NOT EXISTS semantic_dimension_contract (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  dimension VARCHAR(64) NOT NULL,
  allowed_values_json CLOB,
  hierarchy_json CLOB,
  version VARCHAR(32) NOT NULL DEFAULT 'v1',
  status VARCHAR(16) NOT NULL DEFAULT 'active'
);
CREATE TABLE IF NOT EXISTS semantic_business_rule (
  rule_id VARCHAR(64) PRIMARY KEY,
  condition CLOB NOT NULL,
  description CLOB,
  version VARCHAR(32) NOT NULL DEFAULT 'v1',
  status VARCHAR(16) NOT NULL DEFAULT 'active'
);
CREATE TABLE IF NOT EXISTS repair_policy_rule (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  rule_id VARCHAR(64) NOT NULL,
  error_type VARCHAR(64) NOT NULL,
  severity VARCHAR(16) NOT NULL,
  allowed_actions_json CLOB NOT NULL,
  forbidden_json CLOB,
  max_retry INT NOT NULL DEFAULT 1,
  priority INT NOT NULL DEFAULT 0,
  enabled TINYINT NOT NULL DEFAULT 1
);
CREATE TABLE IF NOT EXISTS repair_execution_history (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  session_id VARCHAR(128) NOT NULL,
  loop_index INT,
  error_type VARCHAR(64),
  repair_type VARCHAR(32),
  directive CLOB,
  forbidden_hit TINYINT NOT NULL DEFAULT 0,
  gaming_suspect TINYINT NOT NULL DEFAULT 0,
  outcome VARCHAR(32),
  created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3)
);
CREATE TABLE IF NOT EXISTS golden_dataset_case (
  case_id VARCHAR(64) PRIMARY KEY,
  question CLOB NOT NULL,
  category VARCHAR(64),
  expected_sql CLOB,
  expected_answer CLOB,
  expected_metric VARCHAR(64),
  difficulty VARCHAR(8),
  tags VARCHAR(256),
  version VARCHAR(32) NOT NULL DEFAULT 'v1'
);
CREATE TABLE IF NOT EXISTS golden_evaluation_result (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  eval_id VARCHAR(64) NOT NULL,
  case_id VARCHAR(64) NOT NULL,
  agent_version VARCHAR(32),
  prompt_version VARCHAR(32),
  skill_version VARCHAR(32),
  semantic_version VARCHAR(32),
  actual_answer CLOB,
  trust_score INT,
  verdict VARCHAR(16),
  accuracy_pass TINYINT DEFAULT 0,
  semantic_pass TINYINT DEFAULT 0,
  hallucination_flag TINYINT DEFAULT 0,
  repair_used VARCHAR(32),
  created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3)
);
CREATE TABLE IF NOT EXISTS agent_version_registry (
  version_id VARCHAR(128) PRIMARY KEY,
  component VARCHAR(32) NOT NULL,
  component_ref VARCHAR(256) NOT NULL,
  version VARCHAR(32) NOT NULL,
  checksum VARCHAR(64),
  released_by VARCHAR(64),
  released_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
  golden_eval_id VARCHAR(64),
  status VARCHAR(16) NOT NULL DEFAULT 'candidate'
);
CREATE TABLE IF NOT EXISTS calibration_state (
  id INT DEFAULT 1 PRIMARY KEY,
  pass_threshold INT NOT NULL,
  warn_threshold INT NOT NULL,
  direct_threshold INT NOT NULL,
  hint_threshold INT NOT NULL,
  w_data DOUBLE NOT NULL,
  w_tool DOUBLE NOT NULL,
  w_semantic DOUBLE NOT NULL,
  w_adversarial DOUBLE NOT NULL,
  updated_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3)
);
CREATE TABLE IF NOT EXISTS verification_feedback (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  session_id VARCHAR(128) NOT NULL,
  verdict VARCHAR(16) NOT NULL,
  human_label VARCHAR(16) NOT NULL,
  note VARCHAR(512),
  created_by VARCHAR(64),
  created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3)
);
CREATE TABLE IF NOT EXISTS calibration_apply_pending (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  eval_id VARCHAR(64) NOT NULL UNIQUE,
  pass_before INT NOT NULL,
  warn_before INT NOT NULL,
  started_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
  status VARCHAR(16) NOT NULL DEFAULT 'pending',
  resolved_at DATETIME(3)
);
CREATE TABLE IF NOT EXISTS critic_challenge_stats (
  challenge_type VARCHAR(64) PRIMARY KEY,
  found_count INT NOT NULL DEFAULT 0,
  confirmed_count INT NOT NULL DEFAULT 0,
  updated_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3)
);
CREATE TABLE IF NOT EXISTS rule_experiment (
  experiment_id VARCHAR(64) PRIMARY KEY,
  name VARCHAR(128) NOT NULL,
  candidate_metric_id VARCHAR(64) NOT NULL,
  candidate_direction VARCHAR(16) NOT NULL,
  candidate_deny_aggregation VARCHAR(64),
  traffic_percent INT NOT NULL DEFAULT 0,
  status VARCHAR(16) NOT NULL DEFAULT 'running',
  started_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
  ended_at DATETIME(3)
);

-- seeds
INSERT INTO semantic_metric_contract (metric_id, metric_name, business_definition, formula, unit, direction_higher, aggregation_rule_json, owner, version, status) VALUES
  ('quality_score', '质量评分', '质量缺陷严重程度', 'defect/total', 'score', 'worse', '{"allow":["avg","trend"],"deny":["sum"]}', '质量部', 'v1', 'active'),
  ('sales_amount', '销售额', '销售总额', 'sum(sales)', 'currency', 'better', '{"allow":["sum","avg","trend"],"deny":[]}', '销售部', 'v1', 'active'),
  ('defect_density', '缺陷密度', '单位产品缺陷数', 'defect/total', 'density', 'worse', '{"allow":["avg","trend"],"deny":["sum"]}', '质量部', 'v1', 'active');
INSERT INTO semantic_dimension_contract (dimension, allowed_values_json, version, status) VALUES
  ('department', '["杭州开发一部","杭州开发二部","杭州开发三部","杭州开发四部","杭州开发五部"]', 'v1', 'active'),
  ('application', '["F-CMS","F-Loan","F-Risk","F-Pay","F-Channel"]', 'v1', 'active');
INSERT INTO semantic_business_rule (rule_id, condition, description, version, status) VALUES
  ('sales_growth_rule', 'growth_rate > 0', '销售增长必须基于同比计算', 'v1', 'active');
INSERT INTO repair_policy_rule (rule_id, error_type, severity, allowed_actions_json, forbidden_json, max_retry, priority, enabled) VALUES
  ('rp_semantic_mismatch', 'SEMANTIC_MISMATCH', 'HIGH', '["SEMANTIC_FIX","CLARIFY_USER"]', '["MODIFY_RESULT","CHANGE_CONCLUSION"]', 1, 10, 1),
  ('rp_data_missing', 'DATA_MISSING', 'HIGH', '["DATA_REQUERY","CLARIFY_USER"]', '["MODIFY_RESULT"]', 2, 10, 1),
  ('rp_parameter_error', 'PARAMETER_ERROR', 'MEDIUM', '["PARAMETER_FIX"]', '["MODIFY_RESULT"]', 2, 5, 1),
  ('rp_arithmetic_error', 'ARITHMETIC_ERROR', 'MEDIUM', '["SEMANTIC_FIX"]', '["MODIFY_RESULT"]', 1, 5, 1),
  ('rp_fabrication', 'FABRICATION', 'CRITICAL', '["REFUSE"]', '["MODIFY_RESULT","DATA_REQUERY"]', 0, 20, 1),
  ('rp_question_ambiguous', 'QUESTION_AMBIGUOUS', 'MEDIUM', '["CLARIFY_USER"]', '["MODIFY_RESULT"]', 1, 5, 1);
INSERT INTO golden_dataset_case (case_id, question, category, expected_answer, expected_metric, difficulty, version) VALUES
  ('gq_001', '哪个部门缺陷密度最高？', 'single_query', '杭州开发五部', 'quality_score', 'LOW', 'v1'),
  ('gq_002', '哪个部门质量最好？', 'single_query', '杭州开发三部', 'quality_score', 'LOW', 'v1'),
  ('gq_003', '杭州开发一部和二部哪个质量更差？', 'compare', '杭州开发一部', 'quality_score', 'MEDIUM', 'v1'),
  ('gq_004', '杭州开发一部的缺陷密度是多少？', 'single_query', '23.1', 'quality_score', 'LOW', 'v1');
