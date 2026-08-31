-- Skill runtime routing metadata.
-- Array columns are stored as JSON text for openGauss/MySQL-compatible deployments.
CREATE TABLE IF NOT EXISTS skill_routing_metadata (
  skill_name       VARCHAR(128) PRIMARY KEY,
  short_summary    VARCHAR(3000) NOT NULL DEFAULT '',
  aliases          TEXT NOT NULL DEFAULT '[]',
  keywords         TEXT NOT NULL DEFAULT '[]',
  metric_tags      TEXT NOT NULL DEFAULT '[]',
  domain_tags      TEXT NOT NULL DEFAULT '[]',
  data_source_tags TEXT NOT NULL DEFAULT '[]',
  priority         INT NOT NULL DEFAULT 0,
  active           BOOLEAN NOT NULL DEFAULT TRUE,
  updated_at       TIMESTAMP NOT NULL DEFAULT now(),
  CONSTRAINT fk_skill_routing_metadata_skill
    FOREIGN KEY (skill_name) REFERENCES skill_index(name)
);

CREATE INDEX IF NOT EXISTS idx_skill_routing_metadata_active
  ON skill_routing_metadata(active, priority DESC);
