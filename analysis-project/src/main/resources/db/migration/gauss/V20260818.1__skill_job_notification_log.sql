-- One row per notification delivery attempt. The sender HTTP implementation remains unchanged.
CREATE TABLE skill_job_notification (
  id                BIGSERIAL PRIMARY KEY,
  job_id            BIGINT NOT NULL,
  execution_id      BIGINT NOT NULL,
  request_type      VARCHAR(16) NOT NULL,
  status            VARCHAR(16) NOT NULL,
  trigger_type      VARCHAR(16),
  sender_name       VARCHAR(256),
  recipient_summary VARCHAR(512),
  content_type      VARCHAR(16),
  content           TEXT,
  file_name         VARCHAR(512),
  file_url          TEXT,
  error_msg         TEXT,
  requested_at      TIMESTAMP NOT NULL DEFAULT now(),
  started_at        TIMESTAMP NULL,
  completed_at      TIMESTAMP NULL,
  created_at        TIMESTAMP NOT NULL DEFAULT now()
);

COMMENT ON TABLE skill_job_notification IS 'SkillJob 通知投递记录：每次首发/补发独立留痕';
COMMENT ON COLUMN skill_job_notification.request_type IS 'INITIAL=任务完成首发 / RESEND=人工补发';
COMMENT ON COLUMN skill_job_notification.status IS 'PENDING=已受理 / SENDING=调用中 / SUCCESS=发送器返回成功 / FAILED=发送器异常 / SKIPPED=配置不发送';
CREATE INDEX idx_skill_job_notification_execution ON skill_job_notification(execution_id, id DESC);
CREATE INDEX idx_skill_job_notification_status ON skill_job_notification(status, requested_at DESC);
