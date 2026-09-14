-- Two-stage Skill review persistence for openGauss.
-- Additive only: legacy skill_publish and skill_approval records remain untouched.

CREATE TABLE IF NOT EXISTS skill_review_request (
  id BIGSERIAL PRIMARY KEY,
  skill_id BIGINT NOT NULL REFERENCES skill_manage(id),
  snapshot_json TEXT NOT NULL,
  snapshot_hash VARCHAR(64) NOT NULL,
  status VARCHAR(32) NOT NULL,
  submitter_user_id VARCHAR(128) NOT NULL,
  submitted_at TIMESTAMP,
  final_comment TEXT,
  final_reviewed_at TIMESTAMP,
  created_at TIMESTAMP NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
  CONSTRAINT chk_skill_review_request_status CHECK (
    status IN ('DRAFT', 'DIMENSION_REVIEW', 'FINAL_REVIEW', 'APPROVED', 'REJECTED', 'WITHDRAWN')
  )
);
CREATE INDEX IF NOT EXISTS idx_skill_review_request_status
  ON skill_review_request(status, submitted_at, id);
CREATE INDEX IF NOT EXISTS idx_skill_review_request_submitter
  ON skill_review_request(submitter_user_id, status, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_skill_review_request_skill
  ON skill_review_request(skill_id, created_at DESC);

CREATE TABLE IF NOT EXISTS skill_review_dimension (
  id BIGSERIAL PRIMARY KEY,
  request_id BIGINT NOT NULL REFERENCES skill_review_request(id),
  target_type VARCHAR(64) NOT NULL,
  target_id VARCHAR(255) NOT NULL,
  target_name VARCHAR(255),
  status VARCHAR(32) NOT NULL,
  review_comment TEXT,
  reviewed_at TIMESTAMP,
  created_at TIMESTAMP NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
  CONSTRAINT uk_skill_review_dimension_target UNIQUE (request_id, target_type, target_id),
  CONSTRAINT chk_skill_review_dimension_status CHECK (
    status IN ('PENDING', 'APPROVED', 'REJECTED', 'CANCELLED')
  )
);
CREATE INDEX IF NOT EXISTS idx_skill_review_dimension_status
  ON skill_review_dimension(status, request_id, id);
CREATE INDEX IF NOT EXISTS idx_skill_review_dimension_target
  ON skill_review_dimension(target_type, target_id, status);

CREATE TABLE IF NOT EXISTS skill_review_audit (
  id BIGSERIAL PRIMARY KEY,
  request_id BIGINT NOT NULL REFERENCES skill_review_request(id),
  dimension_id BIGINT REFERENCES skill_review_dimension(id),
  action VARCHAR(64) NOT NULL,
  actor_user_id VARCHAR(128) NOT NULL,
  previous_status VARCHAR(32),
  new_status VARCHAR(32),
  comment TEXT,
  snapshot_hash VARCHAR(64),
  created_at TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_skill_review_audit_request
  ON skill_review_audit(request_id, id);
CREATE INDEX IF NOT EXISTS idx_skill_review_audit_actor
  ON skill_review_audit(actor_user_id, created_at DESC);

INSERT INTO ai_chat_runtime_config (config_key, config_value, config_description)
SELECT 'skill_final_reviewer_user_ids', '[]', 'Skill 开发复核认证账号白名单(JSON 数组)'
WHERE NOT EXISTS (
  SELECT 1 FROM ai_chat_runtime_config
  WHERE config_key = 'skill_final_reviewer_user_ids'
);
