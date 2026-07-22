-- ============================================================================
-- V3.0 V4.0: Critic self-learning challenge stats (Flyway migration)
-- ----------------------------------------------------------------------------
-- Per challenge-type found/confirmed counts. The Critic learns which challenge
-- types actually surface real holes (confirmed = the verdict was FAIL), and
-- emphasizes high-effectiveness types in subsequent critic prompts.
-- ============================================================================

CREATE TABLE IF NOT EXISTS critic_challenge_stats (
  challenge_type VARCHAR(64) PRIMARY KEY,
  found_count INT NOT NULL DEFAULT 0,
  confirmed_count INT NOT NULL DEFAULT 0,
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
