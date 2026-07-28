-- ============================================================================
-- V3.0 V4.0: online Trust calibration + SLO support (Flyway migration)
-- ----------------------------------------------------------------------------
-- verification_feedback - human labels on verdicts (correct/incorrect), the
--   signal for online threshold/weight calibration (V4.0 §31.2 在线标定).
-- calibration_state    - singleton row holding the currently-calibrated trust
--   thresholds/weights, read at runtime by TrustScoreCalculator (mutable overlay
--   over the static harness.a2a.verification.trust.* config).
-- ============================================================================

CREATE TABLE IF NOT EXISTS verification_feedback (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  session_id VARCHAR(128) NOT NULL,
  verdict VARCHAR(16) NOT NULL COMMENT 'pass/warn/fail/unverified (the verdict being labeled)',
  human_label VARCHAR(16) NOT NULL COMMENT 'correct/incorrect',
  note VARCHAR(512) DEFAULT NULL,
  created_by VARCHAR(64) DEFAULT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  INDEX idx_vf_session (session_id),
  INDEX idx_vf_verdict (verdict, human_label)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS calibration_state (
  id INT PRIMARY KEY DEFAULT 1,
  pass_threshold INT NOT NULL,
  warn_threshold INT NOT NULL,
  direct_threshold INT NOT NULL,
  hint_threshold INT NOT NULL,
  w_data DOUBLE NOT NULL,
  w_tool DOUBLE NOT NULL,
  w_semantic DOUBLE NOT NULL,
  w_adversarial DOUBLE NOT NULL,
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
