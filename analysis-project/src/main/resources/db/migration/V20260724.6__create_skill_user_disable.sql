CREATE TABLE IF NOT EXISTS skill_user_disable (
    id          BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    skill_id    BIGINT NOT NULL,
    user_id     VARCHAR(64) NOT NULL,
    created_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_user_skill (user_id, skill_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
