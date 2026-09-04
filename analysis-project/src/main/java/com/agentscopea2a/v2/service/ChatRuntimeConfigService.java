package com.agentscopea2a.v2.service;

import com.agentscopea2a.entity.AiChatRuntimeConfig;
import com.agentscopea2a.mapper.gauss.AiChatRuntimeConfigMapper;
import io.agentscope.core.model.ModelUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Resolves the global /ai/chat limits once for each active conversation. */
@Service
public class ChatRuntimeConfigService {
    public static final String RUNTIME_CONFIG_CTX_KEY = "aiChatRuntimeConfig";
    private static final Logger log = LoggerFactory.getLogger(ChatRuntimeConfigService.class);
    private static final long SESSION_IDLE_TTL_MS = 2 * 60 * 60 * 1000L;
    private static final RuntimeConfig DEFAULT = new RuntimeConfig(120, 3, 1200, 120, false, false);

    private final AiChatRuntimeConfigMapper mapper;
    private final Map<SessionKey, CacheEntry> sessionCache = new ConcurrentHashMap<>();

    public ChatRuntimeConfigService(AiChatRuntimeConfigMapper mapper) {
        this.mapper = mapper;
    }

    public RuntimeConfig resolve(String userId, String conversationId) {
        evictExpiredEntries();
        SessionKey key = new SessionKey(
                userId == null || userId.isBlank() ? "anonymous" : userId,
                conversationId == null ? "" : conversationId);
        return sessionCache.compute(key, (ignored, current) -> {
            if (current != null && !current.isExpired()) {
                return current.touch();
            }
            return new CacheEntry(loadFromDatabase());
        }).config();
    }

    private RuntimeConfig loadFromDatabase() {
        try {
            Map<String, String> values = new ConcurrentHashMap<>();
            for (AiChatRuntimeConfig config : mapper.selectAll()) {
                if (config.getConfigKey() != null && config.getConfigValue() != null) {
                    values.put(config.getConfigKey(), config.getConfigValue());
                }
            }
            RuntimeConfig runtimeConfig = new RuntimeConfig(
                    inRange(values.get("model_timeout_seconds"), 10, 600, DEFAULT.modelTimeoutSeconds()),
                    inRange(values.get("model_retry_count"), 0, 10, DEFAULT.modelRetryCount()),
                    inRange(values.get("stream_timeout_seconds"), 60, 3600, DEFAULT.streamTimeoutSeconds()),
                    inRange(values.get("chunk_gap_timeout_seconds"), 10, 600, DEFAULT.chunkGapTimeoutSeconds()),
                    Boolean.parseBoolean(values.get("long_task_enabled")),
                    Boolean.parseBoolean(values.get("script_exec_enabled")));
            ModelUtils.configureChunkGapTimeoutSeconds(runtimeConfig.chunkGapTimeoutSeconds());
            return runtimeConfig;
        } catch (Exception e) {
            log.warn("Unable to load /ai/chat runtime configuration; using defaults: {}", e.getMessage());
            return DEFAULT;
        }
    }

    private void evictExpiredEntries() {
        sessionCache.entrySet().removeIf(entry -> entry.getValue().isExpired());
    }

    private static int inRange(String value, int min, int max, int fallback) {
        try {
            int parsed = Integer.parseInt(value);
            return parsed >= min && parsed <= max ? parsed : fallback;
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    public record RuntimeConfig(
            int modelTimeoutSeconds,
            int modelRetryCount,
            int streamTimeoutSeconds,
            int chunkGapTimeoutSeconds,
            boolean longTaskEnabled,
            boolean scriptExecEnabled) { }

    private record SessionKey(String userId, String conversationId) { }

    private record CacheEntry(RuntimeConfig config, long expiresAtMs) {
        CacheEntry(RuntimeConfig config) {
            this(config, System.currentTimeMillis() + SESSION_IDLE_TTL_MS);
        }

        boolean isExpired() {
            return System.currentTimeMillis() >= expiresAtMs;
        }

        CacheEntry touch() {
            return new CacheEntry(config);
        }
    }
}
