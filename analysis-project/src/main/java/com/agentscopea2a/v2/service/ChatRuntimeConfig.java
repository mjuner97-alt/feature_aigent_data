package com.agentscopea2a.v2.service;

import java.util.Map;

/** Immutable snapshot of the runtime configuration dictionary for a chat session. */
public final class ChatRuntimeConfig {
    private static final ChatRuntimeConfig EMPTY = new ChatRuntimeConfig(Map.of());

    private final Map<String, String> values;

    public ChatRuntimeConfig(Map<String, String> values) {
        this.values = Map.copyOf(values);
    }

    public static ChatRuntimeConfig empty() {
        return EMPTY;
    }

    public String getOrDefault(String key, String defaultValue) {
        return values.getOrDefault(key, defaultValue);
    }

    public int getIntOrDefault(String key, int defaultValue) {
        try {
            return Integer.parseInt(values.get(key));
        } catch (RuntimeException ignored) {
            return defaultValue;
        }
    }

    public boolean getBooleanOrDefault(String key, boolean defaultValue) {
        String value = values.get(key);
        if ("true".equalsIgnoreCase(value)) {
            return true;
        }
        if ("false".equalsIgnoreCase(value)) {
            return false;
        }
        return defaultValue;
    }
}
