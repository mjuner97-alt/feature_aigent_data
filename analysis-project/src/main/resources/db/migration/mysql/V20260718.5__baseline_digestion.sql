-- ============================================================================
-- P0-1 Flyway baseline: digestion trace tables
-- ----------------------------------------------------------------------------
-- Source: src/main/java/com/agentscopea2a/v2/digestion/TraceMiner.java:570,588
--
-- Tables:
--   user_trace_summary - aggregated tool-call sequences per user/day/fingerprint
--   digestion_log       - per-user per-day digestion run status (Phase 1-4)
-- ============================================================================

CREATE TABLE IF NOT EXISTS user_trace_summary (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id VARCHAR(128) NOT NULL,
  date_key VARCHAR(16) NOT NULL,
  fingerprint VARCHAR(255) NOT NULL,
  runtime_fingerprint VARCHAR(255) DEFAULT NULL COMMENT 'metric fingerprint for L1 skill lookup',
  tool_sequence TEXT NOT NULL,
  success_count INT NOT NULL DEFAULT 0,
  failure_count INT NOT NULL DEFAULT 0,
  failure_score DECIMAL(6,1) NOT NULL DEFAULT 0.0,
  sample_query TEXT,
  user_query TEXT,
  tool_call_details LONGTEXT,
  status VARCHAR(16) DEFAULT 'pending',
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_user_date_fp (user_id, date_key, fingerprint),
  KEY idx_user_date (user_id, date_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS digestion_log (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id VARCHAR(128) NOT NULL,
  date_key VARCHAR(16) NOT NULL,
  phase1_cleaned_ledger INT DEFAULT 0,
  phase2_mined_traces INT DEFAULT 0,
  phase3_skills_evolved INT DEFAULT 0,
  phase4_memory_digested TINYINT(1) DEFAULT 0,
  started_at TIMESTAMP NOT NULL,
  completed_at TIMESTAMP NULL,
  error_msg TEXT,
  KEY idx_user_date (user_id, date_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
