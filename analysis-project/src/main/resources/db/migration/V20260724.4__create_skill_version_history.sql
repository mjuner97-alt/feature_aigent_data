CREATE TABLE IF NOT EXISTS skill_version_history (
    id              BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    skill_id        BIGINT NOT NULL,
    version         INT NOT NULL,
    name            VARCHAR(128),
    description     TEXT,
    content         TEXT,
    category        VARCHAR(64),
    tags            VARCHAR(512),
    edited_by       VARCHAR(64) NOT NULL,
    edit_reason     VARCHAR(256),
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_skill_version (skill_id, version DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
