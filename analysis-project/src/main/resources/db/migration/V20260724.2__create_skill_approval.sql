CREATE TABLE IF NOT EXISTS skill_approval (
    id               BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    publish_id       BIGINT NULL,
    draft_id         BIGINT NULL,
    action           VARCHAR(32) NOT NULL,
    operator         VARCHAR(64) NOT NULL,
    comment          TEXT,
    version_snapshot INT NOT NULL,
    created_at       TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_publish (publish_id),
    KEY idx_draft (draft_id),
    KEY idx_operator (operator)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
