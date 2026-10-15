-- skill_record_url 只保存服务基础地址，接口路径由前端定义。
-- 保留各环境原有主机和端口，不固定 IP；兼容已有完整 record 地址。
UPDATE ai_chat_runtime_config
SET config_value = regexp_replace(trim(config_value), '^(https?://[^/]+).*$', '\1'),
    config_description = '案例服务基础地址（不含接口路径）'
WHERE config_key = 'skill_record_url';
