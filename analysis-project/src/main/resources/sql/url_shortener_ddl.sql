-- URL短链表 — 存储原始URL与对应短码的映射关系，用于避免LLM输出长URL时截断或丢失参数
CREATE TABLE url_shortener (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    short_code VARCHAR(16) NOT NULL UNIQUE COMMENT 'Base62短码，如 aB3xK9mP2qR5tY8w',
    original_url TEXT NOT NULL COMMENT '原始完整URL',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    expires_at TIMESTAMP NULL COMMENT '过期时间，NULL表示永不过期',
    INDEX idx_expires_at (expires_at)
) COMMENT='URL短链映射表';
