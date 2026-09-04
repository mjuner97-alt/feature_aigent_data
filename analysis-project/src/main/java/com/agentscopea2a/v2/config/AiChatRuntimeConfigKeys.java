package com.agentscopea2a.v2.config;

/** Configuration keys stored in {@code ai_chat_runtime_config}. */
public final class AiChatRuntimeConfigKeys {
    public static final String MODEL_TIMEOUT_SECONDS = "model_timeout_seconds";
    public static final String MODEL_RETRY_COUNT = "model_retry_count";
    public static final String STREAM_TIMEOUT_SECONDS = "stream_timeout_seconds";
    public static final String CHUNK_GAP_TIMEOUT_SECONDS = "chunk_gap_timeout_seconds";
    public static final String CONNECT_TIMEOUT_SECONDS = "connect_timeout_seconds";
    public static final String RESPONSE_TIMEOUT_SECONDS = "response_timeout_seconds";
    public static final String LONG_TASK_ENABLED = "long_task_enabled";
    public static final String SCRIPT_EXEC_ENABLED = "script_exec_enabled";

    private AiChatRuntimeConfigKeys() {
    }
}
