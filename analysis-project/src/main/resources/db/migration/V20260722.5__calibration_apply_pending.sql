-- ============================================================================
-- V3.0 V4.0: calibration auto-rollback watcher pending table (Flyway migration)
-- ----------------------------------------------------------------------------
-- Tracks Golden evaluations kicked off by QualityOptimizationLoop.applyThresholdTweaks
-- that are awaiting gate validation. The @Scheduled CalibrationRollbackWatcher polls
-- these rows: when the Golden eval completes, it promotes (gate passed) or rolls the
-- calibration back (gate failed / timed out). Persisted so a restart doesn't lose a
-- pending rollback.
-- ============================================================================

CREATE TABLE IF NOT EXISTS calibration_apply_pending (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  eval_id VARCHAR(64) NOT NULL,
  pass_before INT NOT NULL,
  warn_before INT NOT NULL,
  started_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  status VARCHAR(16) NOT NULL DEFAULT 'pending' COMMENT 'pending/validated/rolled_back',
  resolved_at DATETIME(3) DEFAULT NULL,
  UNIQUE KEY uk_cap_eval (eval_id),
  INDEX idx_cap_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
