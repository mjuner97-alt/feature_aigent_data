package com.agentscopea2a.v2.service;

import com.agentscopea2a.entity.AiChatRuntimeConfig;
import com.agentscopea2a.mapper.gauss.AiChatRuntimeConfigMapper;
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
    private final AiChatRuntimeConfigMapper mapper;
    private final Map<SessionKey, CacheEntry> sessionCache = new ConcurrentHashMap<>();

    public ChatRuntimeConfigService(AiChatRuntimeConfigMapper mapper) {
        this.mapper = mapper;
    }

    public ChatRuntimeConfig resolve(String userId, String conversationId) {
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

    private ChatRuntimeConfig loadFromDatabase() {
        try {
            Map<String, String> values = new ConcurrentHashMap<>();
            for (AiChatRuntimeConfig config : mapper.selectAll()) {
                if (config.getConfigKey() != null && config.getConfigValue() != null) {
                    values.put(config.getConfigKey(), config.getConfigValue());
                }
            }
            return new ChatRuntimeConfig(values);
        } catch (Exception e) {
            log.warn("Unable to load /ai/chat runtime configuration; using defaults: {}", e.getMessage());
            return ChatRuntimeConfig.empty();
        }
    }

    private void evictExpiredEntries() {
        sessionCache.entrySet().removeIf(entry -> entry.getValue().isExpired());
    }

    private record SessionKey(String userId, String conversationId) { }

    private record CacheEntry(ChatRuntimeConfig config, long expiresAtMs) {
        CacheEntry(ChatRuntimeConfig config) {
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
