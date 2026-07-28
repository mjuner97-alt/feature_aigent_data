-- ============================================================================
-- P0-1 Flyway baseline: episodic memory tables
-- ----------------------------------------------------------------------------
-- Source: src/main/java/com/agentscopea2a/v2/memory/MySqlEpisodicMemory.java:491
--         src/main/java/com/agentscopea2a/v2/memory/MysqlMemoryStore.java:68,81
--
-- Tables:
--   QualitySupervisor_episodic_memory - per-session episodic memory (config-driven name)
--   agent_memory                      - per-user MEMORY.md style key-value store
--   agent_memory_ledger               - per-user daily event log (append-only)
--
-- Notes:
--   - Uses CREATE TABLE IF NOT EXISTS so existing DBs are not touched.
--   - New installations get the same schema as a running v2 system.
--   - The episodic_memory table name is hard-coded here; if the
--     harness.a2a.memory.digestion.episodic-table-name property changes,
--     a new migration V<date>__rename_episodic_table.sql must be added.
-- ============================================================================

CREATE TABLE IF NOT EXISTS QualitySupervisor_episodic_memory (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  session_id VARCHAR(255) NOT NULL,
  role VARCHAR(50) NOT NULL,
  content TEXT NOT NULL,
  embedding LONGTEXT DEFAULT NULL,
  status VARCHAR(16) DEFAULT 'active',
  tool_call_details LONGTEXT DEFAULT NULL,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  FULLTEXT INDEX ft_content (content),
  INDEX idx_embedding (embedding(255)),
  INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS agent_memory (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  user_id VARCHAR(128) NOT NULL,
  kind VARCHAR(32) NOT NULL,
  key_name VARCHAR(128) NOT NULL,
  body MEDIUMTEXT NOT NULL,
  version INT NOT NULL DEFAULT 1,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_user_kind_key (user_id, kind, key_name),
  KEY idx_user_updated (user_id, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS agent_memory_ledger (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  user_id VARCHAR(128) NOT NULL,
  date_key VARCHAR(16) NOT NULL,
  source VARCHAR(32) NOT NULL,
  line MEDIUMTEXT NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_user_date (user_id, date_key, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
