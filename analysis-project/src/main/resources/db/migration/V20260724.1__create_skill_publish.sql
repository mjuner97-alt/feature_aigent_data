CREATE TABLE IF NOT EXISTS skill_publish (
    id                       BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    skill_id                 BIGINT NOT NULL,
    target_type              VARCHAR(32) NOT NULL,
    target_id                VARCHAR(64) NOT NULL,
    target_name              VARCHAR(128) NOT NULL,
    status                   VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    submitter                VARCHAR(64) NOT NULL,
    approver                 VARCHAR(64),
    approve_time             TIMESTAMP NULL,
    current_approver_user_id VARCHAR(64),
    last_approval_comment    TEXT,
    last_approval_at         TIMESTAMP NULL,
    created_at               TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_skill (skill_id),
    KEY idx_status (status),
    KEY idx_submitter (submitter),
    KEY idx_approver_pending (current_approver_user_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
