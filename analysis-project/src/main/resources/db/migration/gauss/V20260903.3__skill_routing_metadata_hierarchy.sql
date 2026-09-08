DO $$
BEGIN
  ALTER TABLE skill_routing_metadata
    ADD COLUMN topic_tags TEXT NOT NULL DEFAULT '[]';
EXCEPTION
  WHEN duplicate_column THEN NULL;
END $$;

DO $$
BEGIN
  -- maintainer 不加 NOT NULL DEFAULT ''：openGauss 空字符串兼容模式下 DEFAULT '' 视为 NULL，回填违反约束
  ALTER TABLE skill_routing_metadata
    ADD COLUMN maintainer VARCHAR(128);
EXCEPTION
  WHEN duplicate_column THEN NULL;
END $$;

ALTER TABLE skill_capability_binding
  DROP CONSTRAINT IF EXISTS fk_skill_capability_skill;

ALTER TABLE tool_route_tag_dictionary
  DROP CONSTRAINT IF EXISTS ck_tool_route_tag_dictionary_type;

ALTER TABLE tool_route_tag_dictionary
  ADD CONSTRAINT ck_tool_route_tag_dictionary_type
  CHECK (tag_type IN ('DOMAIN', 'TOPIC', 'METRIC', 'DIMENSION'));
