-- ============================================================================
-- V3.0 Phase 2: add candidate_conclusion to verification_record (for Replay)
-- ----------------------------------------------------------------------------
-- Replay's Version/Model/Contract modes re-run the engine on the stored candidate
-- conclusion, so it must be persisted alongside the verdict. Appended (Flyway runs
-- this once after V20260722.1 created verification_record).
-- ============================================================================

ALTER TABLE verification_record ADD COLUMN candidate_conclusion MEDIUMTEXT NULL
  COMMENT '候选结论文本,供 Replay 重放/重新校验' AFTER candidate_source;
