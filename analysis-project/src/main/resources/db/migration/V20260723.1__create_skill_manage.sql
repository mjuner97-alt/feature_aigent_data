-- ============================================================================
-- skill_manage: Skill 主表(含冗余 like_count 与点赞排序索引)
-- ============================================================================

CREATE TABLE IF NOT EXISTS skill_manage (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  name VARCHAR(128) NOT NULL,
  description TEXT NULL,
  content TEXT NULL,
  category VARCHAR(64) NULL,
  tags VARCHAR(512) NULL,
  owner_user_id VARCHAR(64) NOT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
  like_count BIGINT NOT NULL DEFAULT 0,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted_at TIMESTAMP NULL DEFAULT NULL,
  UNIQUE KEY uk_name (name),
  KEY idx_owner (owner_user_id),
  KEY idx_status (status),
  KEY idx_like_rank (like_count DESC, updated_at DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
