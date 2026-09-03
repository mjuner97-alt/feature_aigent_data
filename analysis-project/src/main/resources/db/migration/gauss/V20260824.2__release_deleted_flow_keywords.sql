DELETE FROM skill_flow_trigger t
USING skill_flow f
WHERE t.flow_id = f.id
  AND f.deleted_at IS NOT NULL;
