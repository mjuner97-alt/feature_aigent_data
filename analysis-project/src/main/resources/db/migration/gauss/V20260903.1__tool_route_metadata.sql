CREATE TABLE IF NOT EXISTS tool_route_tag_dictionary (
  tag_type       VARCHAR(16) NOT NULL,
  tag_name       VARCHAR(64) NOT NULL,
  description    VARCHAR(500) NOT NULL DEFAULT '',
  enabled        BOOLEAN NOT NULL DEFAULT TRUE,
  created_at     TIMESTAMP NOT NULL DEFAULT now(),
  updated_at     TIMESTAMP NOT NULL DEFAULT now(),
  PRIMARY KEY (tag_type, tag_name),
  CONSTRAINT ck_tool_route_tag_dictionary_type CHECK (tag_type IN ('METRIC', 'DIMENSION'))
);

CREATE TABLE IF NOT EXISTS tool_route_metadata (
  tool_id         VARCHAR(128) PRIMARY KEY,
  tool_type       VARCHAR(16) NOT NULL,
  description     VARCHAR(3000) NOT NULL DEFAULT '',
  metric_tags     TEXT NOT NULL DEFAULT '[]',
  dimension_tags  TEXT NOT NULL DEFAULT '[]',
  priority        INT NOT NULL DEFAULT 0,
  enabled         BOOLEAN NOT NULL DEFAULT TRUE,
  created_at      TIMESTAMP NOT NULL DEFAULT now(),
  updated_at      TIMESTAMP NOT NULL DEFAULT now(),
  CONSTRAINT ck_tool_route_metadata_type CHECK (tool_type IN ('SQL', 'API', 'SCRIPT'))
);

CREATE INDEX IF NOT EXISTS idx_tool_route_metadata_active_priority
  ON tool_route_metadata(enabled, priority DESC, tool_id);
