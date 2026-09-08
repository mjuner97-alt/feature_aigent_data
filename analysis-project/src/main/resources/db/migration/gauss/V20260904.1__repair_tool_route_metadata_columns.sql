-- Repair migration for environments where the initial route metadata migration
-- was already recorded before topic_tags was added.
DO $$
BEGIN
  ALTER TABLE tool_route_metadata
    ADD COLUMN topic_tags TEXT NOT NULL DEFAULT '[]';
EXCEPTION
  WHEN duplicate_column THEN NULL;
END $$;
