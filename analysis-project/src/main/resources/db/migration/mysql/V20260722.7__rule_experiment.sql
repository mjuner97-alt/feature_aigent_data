-- ============================================================================
-- V3.0 V4.0: A/B rule experiment framework (Flyway migration)
-- ----------------------------------------------------------------------------
-- rule_experiment      - a candidate Semantic-Contract rule (metric direction /
--   aggregation) applied to a traffic bucket of requests, to measure whether it
--   catches real issues (higher fail-rate vs baseline) without harm.
-- verification_record.experiment_id - marks which A/B experiment a verdict belongs
--   to, so measure() can compare the experiment bucket vs the baseline bucket.
-- ============================================================================

CREATE TABLE IF NOT EXISTS rule_experiment (
  experiment_id VARCHAR(64) PRIMARY KEY,
  name VARCHAR(128) NOT NULL,
  candidate_metric_id VARCHAR(64) NOT NULL,
  candidate_direction VARCHAR(16) NOT NULL COMMENT 'worse|better',
  candidate_deny_aggregation VARCHAR(64) DEFAULT NULL COMMENT 'e.g. sum (nullable)',
  traffic_percent INT NOT NULL DEFAULT 0 COMMENT '0-100 bucket size',
  status VARCHAR(16) NOT NULL DEFAULT 'running' COMMENT 'running/promoted/rolled_back',
  started_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  ended_at DATETIME(3) DEFAULT NULL,
  INDEX idx_re_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

ALTER TABLE verification_record ADD COLUMN experiment_id VARCHAR(64) DEFAULT NULL
  COMMENT 'A/B 实验ID(候选规则桶)' AFTER trigger_level;
