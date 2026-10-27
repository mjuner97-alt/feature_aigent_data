-- Password authentication and one-time notification-code recovery.
-- Authentication state is kept separately; developer_pl_person_info is never modified.
INSERT INTO ai_chat_runtime_config (config_key, config_value, config_description)
SELECT 'password_auth_enabled', 'false', '是否开启普通账号密码校验；测试环境可配置为 false'
WHERE NOT EXISTS (SELECT 1 FROM ai_chat_runtime_config WHERE config_key = 'password_auth_enabled');

CREATE TABLE IF NOT EXISTS auth_user_credential (
  user_id VARCHAR(128) PRIMARY KEY, password_hash VARCHAR(255) NOT NULL,
  password_changed_at TIMESTAMP, login_failures INTEGER NOT NULL DEFAULT 0,
  locked_until TIMESTAMP, session_version BIGINT NOT NULL DEFAULT 0,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE auth_user_credential IS '账号密码认证凭据及登录失败限制状态';
COMMENT ON COLUMN auth_user_credential.user_id IS '统一认证号';
COMMENT ON COLUMN auth_user_credential.password_hash IS '密码哈希值';
COMMENT ON COLUMN auth_user_credential.password_changed_at IS '密码修改时间';
COMMENT ON COLUMN auth_user_credential.login_failures IS '连续登录失败次数';
COMMENT ON COLUMN auth_user_credential.locked_until IS '账号锁定截止时间';
COMMENT ON COLUMN auth_user_credential.session_version IS '会话版本号，密码重置时递增';
COMMENT ON COLUMN auth_user_credential.created_at IS '创建时间';

CREATE TABLE IF NOT EXISTS auth_password_reset_code (
  id BIGSERIAL PRIMARY KEY, user_id VARCHAR(128) NOT NULL, code_hash VARCHAR(255) NOT NULL,
  expires_at TIMESTAMP NOT NULL, attempts INTEGER NOT NULL DEFAULT 0, used_at TIMESTAMP,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE auth_password_reset_code IS '密码找回一次性验证码记录';
COMMENT ON COLUMN auth_password_reset_code.id IS '验证码记录主键';
COMMENT ON COLUMN auth_password_reset_code.user_id IS '统一认证号';
COMMENT ON COLUMN auth_password_reset_code.code_hash IS '验证码哈希值';
COMMENT ON COLUMN auth_password_reset_code.expires_at IS '验证码过期时间';
COMMENT ON COLUMN auth_password_reset_code.attempts IS '验证码连续错误次数';
COMMENT ON COLUMN auth_password_reset_code.used_at IS '验证码消费时间';
COMMENT ON COLUMN auth_password_reset_code.created_at IS '创建时间';

CREATE INDEX IF NOT EXISTS idx_auth_reset_user_expiry ON auth_password_reset_code(user_id, expires_at, used_at);
CREATE TABLE IF NOT EXISTS auth_session (
  id VARCHAR(128) PRIMARY KEY, user_id VARCHAR(128) NOT NULL,
  revoked_at TIMESTAMP, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE auth_session IS '账号登录会话及撤销状态';
COMMENT ON COLUMN auth_session.id IS '会话标识';
COMMENT ON COLUMN auth_session.user_id IS '统一认证号';
COMMENT ON COLUMN auth_session.revoked_at IS '会话撤销时间';
COMMENT ON COLUMN auth_session.created_at IS '创建时间';
