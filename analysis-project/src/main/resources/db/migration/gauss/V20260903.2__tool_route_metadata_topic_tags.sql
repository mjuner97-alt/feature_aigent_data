DO $$
BEGIN
  ALTER TABLE tool_route_metadata
    ADD COLUMN topic_tags TEXT NOT NULL DEFAULT '[]';
EXCEPTION
  WHEN duplicate_column THEN NULL;
END $$;

ALTER TABLE tool_route_tag_dictionary
  DROP CONSTRAINT IF EXISTS ck_tool_route_tag_dictionary_type;

ALTER TABLE tool_route_tag_dictionary
  ADD CONSTRAINT ck_tool_route_tag_dictionary_type
  CHECK (tag_type IN ('TOPIC', 'METRIC', 'DIMENSION'));
