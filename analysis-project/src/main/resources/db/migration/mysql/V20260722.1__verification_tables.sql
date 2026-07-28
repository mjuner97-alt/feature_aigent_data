-- ============================================================================
-- V3.0 Verification Agent tables (Flyway migration)
-- ----------------------------------------------------------------------------
-- Source: doc/featuredatanew/validation-agent-design-V3.0.md §23
--
-- Tables:
--   verification_event         - execution trace mirror (aligns with JsonlTraceExporter)
--   verification_record        - per-checkpoint verdict + trust score + repair_type
--   semantic_metric_contract   - P0: metric semantics (direction / aggregation)
--   semantic_dimension_contract- P0: allowed dimension values
--   semantic_business_rule     - P0: enterprise business rules
--   repair_policy_rule         - P0: repair action taxonomy (governance mirror of repair_policy.yaml)
--   repair_execution_history   - repair dispatch audit (anti-gaming)
--
-- Notes:
--   - CREATE TABLE IF NOT EXISTS so existing DBs are untouched (matches baseline style).
--   - Seed inserts use ON DUPLICATE KEY UPDATE so re-running is idempotent.
-- ============================================================================

CREATE TABLE IF NOT EXISTS verification_event (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  event_id VARCHAR(64) NOT NULL,
  session_id VARCHAR(128) NOT NULL,
  type VARCHAR(32) NOT NULL COMMENT 'AGENT_STARTED/TOOL_CALL_STARTED/.../ERROR_OCCURRED',
  actor VARCHAR(64) NOT NULL,
  parent_event_id VARCHAR(64) DEFAULT NULL,
  payload_json MEDIUMTEXT,
  created_ts BIGINT NOT NULL COMMENT 'HookEvent.getTimestamp()',
  INDEX idx_ve_session (session_id),
  INDEX idx_ve_session_ts (session_id, created_ts)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS verification_record (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  session_id VARCHAR(128) NOT NULL,
  user_id VARCHAR(64) DEFAULT NULL,
  checkpoint VARCHAR(32) NOT NULL COMMENT 'subagent-exit/supervisor-exit/per-critical-tool',
  trigger_level VARCHAR(8) DEFAULT NULL COMMENT 'LOW/MEDIUM/HIGH',
  candidate_source VARCHAR(64) NOT NULL,
  trust_score INT DEFAULT NULL,
  verdict VARCHAR(16) NOT NULL COMMENT 'pass/warn/fail/unverified',
  dim_tool INT DEFAULT NULL,
  dim_data INT DEFAULT NULL,
  dim_semantic INT DEFAULT NULL,
  dim_adversarial INT DEFAULT NULL,
  dim_evidence INT DEFAULT NULL,
  dim_freshness INT DEFAULT NULL,
  repair_type VARCHAR(32) DEFAULT NULL,
  summary TEXT,
  issues_json TEXT,
  corrections_json TEXT,
  loop_index INT DEFAULT 0,
  model_name VARCHAR(64) DEFAULT NULL,
  latency_ms BIGINT DEFAULT NULL,
  version_snapshot_json TEXT,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  INDEX idx_vr_session (session_id),
  INDEX idx_vr_verdict_created (verdict, created_at),
  INDEX idx_vr_repair (repair_type),
  INDEX idx_vr_trigger (trigger_level)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ===== P0: Semantic Contract Registry =====

CREATE TABLE IF NOT EXISTS semantic_metric_contract (
  metric_id VARCHAR(64) PRIMARY KEY,
  metric_name VARCHAR(128) NOT NULL,
  business_definition TEXT,
  formula TEXT,
  unit VARCHAR(32) DEFAULT NULL,
  direction_higher VARCHAR(16) NOT NULL DEFAULT 'better' COMMENT 'worse|better',
  aggregation_rule_json VARCHAR(256) DEFAULT NULL COMMENT '{"allow":["avg","trend"],"deny":["sum"]}',
  owner VARCHAR(64) DEFAULT NULL,
  version VARCHAR(32) NOT NULL DEFAULT 'v1',
  status VARCHAR(16) NOT NULL DEFAULT 'active',
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS semantic_dimension_contract (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  dimension VARCHAR(64) NOT NULL,
  allowed_values_json TEXT,
  hierarchy_json TEXT,
  version VARCHAR(32) NOT NULL DEFAULT 'v1',
  status VARCHAR(16) NOT NULL DEFAULT 'active',
  UNIQUE KEY uk_sdc_dim (dimension, version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS semantic_business_rule (
  rule_id VARCHAR(64) PRIMARY KEY,
  `condition` TEXT NOT NULL,
  description TEXT,
  version VARCHAR(32) NOT NULL DEFAULT 'v1',
  status VARCHAR(16) NOT NULL DEFAULT 'active'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ===== P0: Repair Policy Engine =====

CREATE TABLE IF NOT EXISTS repair_policy_rule (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  rule_id VARCHAR(64) NOT NULL,
  error_type VARCHAR(64) NOT NULL,
  severity VARCHAR(16) NOT NULL COMMENT 'LOW/MEDIUM/HIGH/CRITICAL',
  allowed_actions_json TEXT NOT NULL,
  forbidden_json TEXT,
  max_retry INT NOT NULL DEFAULT 1,
  priority INT NOT NULL DEFAULT 0,
  enabled TINYINT NOT NULL DEFAULT 1,
  UNIQUE KEY uk_rpr_rule (rule_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS repair_execution_history (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  session_id VARCHAR(128) NOT NULL,
  loop_index INT DEFAULT 0,
  error_type VARCHAR(64) DEFAULT NULL,
  repair_type VARCHAR(32) DEFAULT NULL,
  directive TEXT,
  forbidden_hit TINYINT NOT NULL DEFAULT 0 COMMENT 'gaming suspect flag',
  gaming_suspect TINYINT NOT NULL DEFAULT 0,
  outcome VARCHAR(32) DEFAULT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  INDEX idx_reh_session (session_id),
  INDEX idx_reh_gaming (gaming_suspect)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ===== Seed: metric contracts (deterministic direction/aggregation for B4) =====

INSERT INTO semantic_metric_contract
  (metric_id, metric_name, business_definition, formula, unit, direction_higher, aggregation_rule_json, owner, version, status)
VALUES
  ('quality_score', '质量评分', '用于衡量产品质量问题严重程度', 'defect_count / total_product', 'score', 'worse',
   '{"allow":["avg","trend"],"deny":["sum"]}', '质量部', 'v1', 'active')
ON DUPLICATE KEY UPDATE
  direction_higher=VALUES(direction_higher), aggregation_rule_json=VALUES(aggregation_rule_json);

INSERT INTO semantic_metric_contract
  (metric_id, metric_name, business_definition, formula, unit, direction_higher, aggregation_rule_json, owner, version, status)
VALUES
  ('sales_amount', '销售额', '区域/周期销售总额', 'sum(sales)', 'currency', 'better',
   '{"allow":["sum","avg","trend"],"deny":[]}', '销售部', 'v1', 'active')
ON DUPLICATE KEY UPDATE
  direction_higher=VALUES(direction_higher), aggregation_rule_json=VALUES(aggregation_rule_json);

INSERT INTO semantic_metric_contract
  (metric_id, metric_name, business_definition, formula, unit, direction_higher, aggregation_rule_json, owner, version, status)
VALUES
  ('defect_density', '缺陷密度', '单位产品缺陷数, 越高越差', 'defect_count / total_product', 'density', 'worse',
   '{"allow":["avg","trend"],"deny":["sum"]}', '质量部', 'v1', 'active')
ON DUPLICATE KEY UPDATE
  direction_higher=VALUES(direction_higher), aggregation_rule_json=VALUES(aggregation_rule_json);

-- ===== Seed: dimension contracts =====

INSERT INTO semantic_dimension_contract (dimension, allowed_values_json, version, status)
VALUES ('department',
  '["杭州开发一部","杭州开发二部","杭州开发三部","杭州开发四部","杭州开发五部"]', 'v1', 'active')
ON DUPLICATE KEY UPDATE allowed_values_json=VALUES(allowed_values_json);

INSERT INTO semantic_dimension_contract (dimension, allowed_values_json, version, status)
VALUES ('application', '["F-CMS","F-Loan","F-Risk","F-Pay","F-Channel"]', 'v1', 'active')
ON DUPLICATE KEY UPDATE allowed_values_json=VALUES(allowed_values_json);

-- ===== Seed: business rules =====

INSERT INTO semantic_business_rule (rule_id, `condition`, description, version, status)
VALUES ('sales_growth_rule', 'growth_rate > 0',
  '销售增长必须基于同比计算, 不可直接比绝对值', 'v1', 'active')
ON DUPLICATE KEY UPDATE `condition`=VALUES(`condition`), description=VALUES(description);

-- ===== Seed: repair policy rules (mirror of repair_policy.yaml) =====

INSERT INTO repair_policy_rule
  (rule_id, error_type, severity, allowed_actions_json, forbidden_json, max_retry, priority, enabled)
VALUES
  ('rp_semantic_mismatch', 'SEMANTIC_MISMATCH', 'HIGH',
   '["SEMANTIC_FIX","CLARIFY_USER"]', '["MODIFY_RESULT","CHANGE_CONCLUSION"]', 1, 10, 1),
  ('rp_data_missing', 'DATA_MISSING', 'HIGH',
   '["DATA_REQUERY","CLARIFY_USER"]', '["MODIFY_RESULT"]', 2, 10, 1),
  ('rp_parameter_error', 'PARAMETER_ERROR', 'MEDIUM',
   '["PARAMETER_FIX"]', '["MODIFY_RESULT"]', 2, 5, 1),
  ('rp_arithmetic_error', 'ARITHMETIC_ERROR', 'MEDIUM',
   '["SEMANTIC_FIX"]', '["MODIFY_RESULT"]', 1, 5, 1),
  ('rp_fabrication', 'FABRICATION', 'CRITICAL',
   '["REFUSE"]', '["MODIFY_RESULT","DATA_REQUERY"]', 0, 20, 1),
  ('rp_question_ambiguous', 'QUESTION_AMBIGUOUS', 'MEDIUM',
   '["CLARIFY_USER"]', '["MODIFY_RESULT"]', 1, 5, 1)
ON DUPLICATE KEY UPDATE
  allowed_actions_json=VALUES(allowed_actions_json),
  forbidden_json=VALUES(forbidden_json),
  max_retry=VALUES(max_retry),
  priority=VALUES(priority),
  enabled=VALUES(enabled);
