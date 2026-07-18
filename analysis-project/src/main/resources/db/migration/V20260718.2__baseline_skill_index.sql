-- ============================================================================
-- P0-1 Flyway baseline: skill index + skill candidate tables
-- ----------------------------------------------------------------------------
-- Source: src/main/java/com/agentscopea2a/v2/skills/SkillIndexRepository.java:41
--         src/main/java/com/agentscopea2a/v2/skills/SkillCandidateRepository.java:43
--
-- Tables:
--   skill_index      - registry of all skills (auto-synth + user-generated)
--   skill_candidate  - pending fingerprints awaiting distillation
-- ============================================================================

CREATE TABLE IF NOT EXISTS skill_index (
  name VARCHAR(128) PRIMARY KEY,
  fingerprint VARCHAR(255) NULL COMMENT 'PR3 L1 lookup key, NULL until then',
  description TEXT,
  embedding LONGTEXT NULL COMMENT 'PR3 reserved; JSON-encoded float[] for MySQL<8.4',
  version INT NOT NULL DEFAULT 1,
  usage_count INT NOT NULL DEFAULT 0,
  success_count INT NOT NULL DEFAULT 0,
  failure_count INT NOT NULL DEFAULT 0,
  last_used TIMESTAMP NULL,
  evolving BOOLEAN NOT NULL DEFAULT FALSE COMMENT 'PR4 cross-JVM evolve lock',
  status VARCHAR(16) NOT NULL DEFAULT 'active',
  source VARCHAR(16) NOT NULL DEFAULT 'auto_synthesized'
    COMMENT 'skill origin: user_generated | auto_synthesized',
  tool_sequence_fingerprint VARCHAR(255) DEFAULT NULL COMMENT 'Phase 3 offline lookup key (tool-id sequence)',
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  KEY idx_status (status),
  KEY idx_source (source),
  KEY idx_tool_seq_fp (tool_sequence_fingerprint)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS skill_candidate (
  fingerprint VARCHAR(255) PRIMARY KEY,
  user_id VARCHAR(64) NOT NULL,
  hit_count INT NOT NULL DEFAULT 0,
  last_query TEXT,
  last_trace_id VARCHAR(64) NULL,
  metric_tag VARCHAR(64) DEFAULT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'pending',
  synth_skill VARCHAR(128) NULL,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  KEY idx_user_status (user_id, status),
  KEY idx_hit_count (hit_count DESC),
  KEY idx_metric_tag (metric_tag)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
