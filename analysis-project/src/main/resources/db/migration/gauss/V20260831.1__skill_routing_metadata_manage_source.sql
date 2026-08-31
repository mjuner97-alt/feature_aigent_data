-- Make skill_manage the source for the Skill routing configuration page.
-- skill_index remains a runtime retrieval index and is intentionally not used
-- to decide which Skills are configurable.
ALTER TABLE skill_routing_metadata
  DROP CONSTRAINT IF EXISTS fk_skill_routing_metadata_skill;

CREATE INDEX IF NOT EXISTS idx_skill_manage_retrieval_active
  ON skill_manage(retrieval_name, status, deleted_at);
