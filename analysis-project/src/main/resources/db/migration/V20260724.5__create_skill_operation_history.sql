CREATE TABLE IF NOT EXISTS skill_operation_history (
    id          BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    skill_id    BIGINT,
    publish_id  BIGINT,
    operator    VARCHAR(64) NOT NULL,
    operation   VARCHAR(64) NOT NULL,
    before_data TEXT,
    after_data  TEXT,
    created_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_skill (skill_id),
    KEY idx_publish (publish_id),
    KEY idx_operator_time (operator, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
