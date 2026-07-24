CREATE TABLE IF NOT EXISTS skill_draft (
    id              BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    skill_id        BIGINT NOT NULL,
    name            VARCHAR(128),
    description     TEXT,
    content         TEXT,
    category        VARCHAR(64),
    tags            VARCHAR(512),
    status          VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    submitter       VARCHAR(64) NOT NULL,
    approver        VARCHAR(64),
    approve_comment TEXT,
    submitted_at    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    approved_at     TIMESTAMP NULL,
    KEY idx_skill (skill_id),
    KEY idx_status (status),
    KEY idx_submitter (submitter)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
