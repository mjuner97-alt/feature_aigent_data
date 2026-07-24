-- ============================================================================
-- skill_like: 点赞记录表(每用户每 Skill 仅一条,唯一约束防重)
-- ============================================================================

CREATE TABLE IF NOT EXISTS skill_like (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  skill_id BIGINT NOT NULL,
  user_id VARCHAR(64) NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_user_skill (user_id, skill_id),
  KEY idx_skill (skill_id),
  KEY idx_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
