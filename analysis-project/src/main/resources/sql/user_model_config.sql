-- 用户模型配置表
-- 用于存储每个用户的模型提供商、API Key、模型名等配置信息
CREATE TABLE IF NOT EXISTS `user_model_config` (
    `user_id` BIGINT PRIMARY KEY COMMENT '用户ID',
    `provider` VARCHAR(32) NOT NULL COMMENT '模型提供商（glm/openai/anthropic）',
    `token` VARCHAR(512) NOT NULL COMMENT '用户的API Key',
    `model_name` VARCHAR(128) NOT NULL COMMENT '模型名',
    `request_url` VARCHAR(512) COMMENT '请求地址',
    `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户模型配置表';
