DO $$
BEGIN
  ALTER TABLE tool_route_metadata
    ADD COLUMN access_scope VARCHAR(16) NOT NULL DEFAULT 'GLOBAL';
EXCEPTION
  WHEN duplicate_column THEN NULL;
END $$;

ALTER TABLE tool_route_metadata
  DROP CONSTRAINT IF EXISTS ck_tool_route_metadata_access_scope;

ALTER TABLE tool_route_metadata
  ADD CONSTRAINT ck_tool_route_metadata_access_scope
  CHECK (access_scope IN ('GLOBAL', 'SKILL_BOUND'));

CREATE TABLE IF NOT EXISTS tool_route_skill_binding (
    tool_id VARCHAR(128) NOT NULL,
    skill_name VARCHAR(255) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    updated_at TIMESTAMP NOT NULL DEFAULT now(),
    PRIMARY KEY (tool_id, skill_name)
);

CREATE INDEX IF NOT EXISTS idx_tool_route_skill_binding_skill
    ON tool_route_skill_binding(skill_name, enabled, tool_id);
