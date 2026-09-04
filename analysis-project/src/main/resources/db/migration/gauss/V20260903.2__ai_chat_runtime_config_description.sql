ALTER TABLE ai_chat_runtime_config
    ADD COLUMN IF NOT EXISTS config_description VARCHAR(512);

UPDATE ai_chat_runtime_config
SET config_description = CASE config_key
    WHEN 'model_timeout_seconds' THEN '模型请求首包等待时间，单位秒；超时后本次模型调用失败。有效范围：10-600。'
    WHEN 'model_retry_count' THEN '主模型请求失败后的重试次数；0 表示不重试，耗尽后切换备用模型。有效范围：0-10。'
    WHEN 'stream_timeout_seconds' THEN '整个 /ai/chat SSE 流允许的最长处理时间，单位秒；超时后停止模型和工具执行。有效范围：60-3600。'
    WHEN 'chunk_gap_timeout_seconds' THEN '模型流相邻 chunk 之间允许的最大间隔，单位秒；超时后本次模型调用失败。有效范围：10-600。'
    ELSE config_description
END
WHERE config_key IN ('model_timeout_seconds', 'model_retry_count', 'stream_timeout_seconds', 'chunk_gap_timeout_seconds');

ALTER TABLE ai_chat_runtime_config
    ALTER COLUMN config_description SET NOT NULL;
