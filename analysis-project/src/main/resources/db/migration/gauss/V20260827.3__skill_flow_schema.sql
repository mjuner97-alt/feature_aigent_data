-- Skill Flow runtime schema for openGauss.
-- Idempotent: safe to run on an existing database without removing data.

CREATE TABLE IF NOT EXISTS skill_flow (
  id BIGSERIAL PRIMARY KEY,
  code VARCHAR(128) NOT NULL,
  name VARCHAR(255) NOT NULL,
  description TEXT,
  task_question TEXT NOT NULL,
  summary_question_template TEXT,
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  max_parallelism INT NOT NULL DEFAULT 1,
  notify_enabled BOOLEAN NOT NULL DEFAULT FALSE,
  created_by VARCHAR(64),
  created_at TIMESTAMP NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
  deleted_at TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_skill_flow_code ON skill_flow(code);
CREATE INDEX IF NOT EXISTS idx_skill_flow_enabled ON skill_flow(enabled, deleted_at);

CREATE TABLE IF NOT EXISTS skill_flow_node (
  id BIGSERIAL PRIMARY KEY,
  flow_id BIGINT NOT NULL REFERENCES skill_flow(id),
  node_key VARCHAR(128) NOT NULL,
  skill_id BIGINT,
  question_template TEXT NOT NULL,
  depends_on_json TEXT NOT NULL DEFAULT '[]',
  required BOOLEAN NOT NULL DEFAULT TRUE,
  max_attempts INT NOT NULL DEFAULT 3,
  sort_order INT NOT NULL DEFAULT 0,
  created_at TIMESTAMP NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
  CONSTRAINT uk_skill_flow_node_key UNIQUE (flow_id, node_key)
);
CREATE INDEX IF NOT EXISTS idx_skill_flow_node_flow ON skill_flow_node(flow_id, sort_order, id);

CREATE TABLE IF NOT EXISTS skill_flow_node_metric (
  id BIGSERIAL PRIMARY KEY,
  flow_node_id BIGINT NOT NULL REFERENCES skill_flow_node(id),
  metric_id BIGINT NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT NOW(),
  CONSTRAINT uk_skill_flow_node_metric UNIQUE (flow_node_id, metric_id)
);
CREATE INDEX IF NOT EXISTS idx_skill_flow_node_metric_metric ON skill_flow_node_metric(metric_id);

CREATE TABLE IF NOT EXISTS skill_flow_trigger (
  id BIGSERIAL PRIMARY KEY,
  flow_id BIGINT NOT NULL REFERENCES skill_flow(id),
  keyword VARCHAR(255) NOT NULL,
  normalized_keyword VARCHAR(255) NOT NULL,
  priority INT NOT NULL DEFAULT 0,
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  created_by VARCHAR(64),
  created_at TIMESTAMP NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
  CONSTRAINT uk_skill_flow_trigger_keyword UNIQUE (normalized_keyword)
);
CREATE INDEX IF NOT EXISTS idx_skill_flow_trigger_enabled ON skill_flow_trigger(enabled, priority DESC);

CREATE TABLE IF NOT EXISTS skill_metric_readiness (
  id BIGSERIAL PRIMARY KEY,
  metric_id BIGINT NOT NULL,
  metric_code VARCHAR(128),
  data_date DATE NOT NULL,
  status VARCHAR(32) NOT NULL,
  ready_at TIMESTAMP,
  expires_at TIMESTAMP,
  metadata_json TEXT,
  created_at TIMESTAMP NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
  CONSTRAINT uk_skill_metric_readiness UNIQUE (metric_id, data_date)
);
CREATE INDEX IF NOT EXISTS idx_skill_metric_readiness_date ON skill_metric_readiness(data_date, status);

CREATE TABLE IF NOT EXISTS skill_flow_execution (
  id BIGSERIAL PRIMARY KEY,
  flow_id BIGINT NOT NULL REFERENCES skill_flow(id),
  flow_code VARCHAR(128) NOT NULL,
  flow_name VARCHAR(255) NOT NULL,
  summary_question_template_snapshot TEXT,
  rendered_summary_question TEXT,
  max_parallelism_snapshot INT NOT NULL,
  notify_enabled_snapshot BOOLEAN NOT NULL DEFAULT FALSE,
  trigger_type VARCHAR(32) NOT NULL,
  trigger_user_id VARCHAR(64),
  conversation_id VARCHAR(128),
  original_question TEXT NOT NULL,
  data_date DATE NOT NULL,
  status VARCHAR(32) NOT NULL,
  active_guard_key VARCHAR(512),
  required_metric_count INT NOT NULL DEFAULT 0,
  ready_metric_count INT NOT NULL DEFAULT 0,
  missing_metrics_json TEXT,
  summary_json TEXT,
  report_path TEXT,
  cancel_requested_at TIMESTAMP,
  started_at TIMESTAMP,
  completed_at TIMESTAMP,
  created_at TIMESTAMP NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_skill_flow_execution_guard ON skill_flow_execution(active_guard_key);
CREATE INDEX IF NOT EXISTS idx_skill_flow_execution_status ON skill_flow_execution(status, created_at);
CREATE INDEX IF NOT EXISTS idx_skill_flow_execution_conversation ON skill_flow_execution(trigger_user_id, conversation_id, created_at);

CREATE TABLE IF NOT EXISTS skill_flow_node_execution (
  id BIGSERIAL PRIMARY KEY,
  flow_execution_id BIGINT NOT NULL REFERENCES skill_flow_execution(id),
  node_key VARCHAR(128) NOT NULL,
  skill_id BIGINT,
  skill_name VARCHAR(255),
  skill_retrieval_name VARCHAR(255),
  question_template_snapshot TEXT,
  rendered_question TEXT,
  depends_on_json TEXT,
  required BOOLEAN NOT NULL DEFAULT TRUE,
  status VARCHAR(32) NOT NULL,
  attempt_count INT NOT NULL DEFAULT 0,
  max_attempts INT NOT NULL DEFAULT 3,
  next_run_at TIMESTAMP,
  lease_owner VARCHAR(128),
  lease_expires_at TIMESTAMP,
  result_json TEXT,
  artifact_path TEXT,
  error_code VARCHAR(128),
  error_message TEXT,
  started_at TIMESTAMP,
  completed_at TIMESTAMP,
  created_at TIMESTAMP NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
  CONSTRAINT uk_skill_flow_node_execution_key UNIQUE (flow_execution_id, node_key)
);
CREATE INDEX IF NOT EXISTS idx_skill_flow_node_execution_sched ON skill_flow_node_execution(status, next_run_at, lease_expires_at);
CREATE INDEX IF NOT EXISTS idx_skill_flow_node_execution_flow ON skill_flow_node_execution(flow_execution_id, status);

CREATE TABLE IF NOT EXISTS skill_flow_node_attempt (
  id BIGSERIAL PRIMARY KEY,
  node_execution_id BIGINT NOT NULL REFERENCES skill_flow_node_execution(id),
  attempt_no INT NOT NULL,
  status VARCHAR(32) NOT NULL,
  retryable BOOLEAN NOT NULL DEFAULT FALSE,
  error_code VARCHAR(128),
  error_message TEXT,
  started_at TIMESTAMP,
  completed_at TIMESTAMP,
  duration_ms BIGINT,
  created_at TIMESTAMP NOT NULL DEFAULT NOW(),
  CONSTRAINT uk_skill_flow_node_attempt_no UNIQUE (node_execution_id, attempt_no)
);
CREATE INDEX IF NOT EXISTS idx_skill_flow_node_attempt_node ON skill_flow_node_attempt(node_execution_id, attempt_no);

CREATE TABLE IF NOT EXISTS skill_flow_notification (
  id BIGSERIAL PRIMARY KEY,
  flow_execution_id BIGINT NOT NULL REFERENCES skill_flow_execution(id),
  delivery_key VARCHAR(512) NOT NULL,
  status VARCHAR(32) NOT NULL,
  recipient VARCHAR(255),
  channel VARCHAR(64),
  request_json TEXT,
  response_json TEXT,
  error_message TEXT,
  sent_at TIMESTAMP,
  created_at TIMESTAMP NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
  CONSTRAINT uk_skill_flow_notification_delivery UNIQUE (delivery_key)
);
CREATE INDEX IF NOT EXISTS idx_skill_flow_notification_execution ON skill_flow_notification(flow_execution_id, id DESC);
