-- ============================================================================
-- skill_reference: 引用关系表(creator=引用者;source=target=被引用 Skill,与 spec §5.6 一致)
-- "我使用的 Skill" = SELECT target_skill_id WHERE creator = 当前用户
-- ============================================================================

CREATE TABLE IF NOT EXISTS skill_reference (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  source_skill_id BIGINT NOT NULL,
  target_skill_id BIGINT NOT NULL,
  creator VARCHAR(64) NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_source_target_creator (source_skill_id, target_skill_id, creator),
  KEY idx_creator (creator),
  KEY idx_target (target_skill_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
