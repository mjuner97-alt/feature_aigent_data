/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 */
package com.agentscopea2a.harness.config;

import io.agentscope.harness.agent.store.BaseStore;
import io.agentscope.harness.agent.store.StoreItem;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.UnifiedJedis;

/**
 * Minimal Redis-backed {@link BaseStore} for multi-replica deployments.
 *
 * <p>Stores each item as a Jackson-serialized JSON string under the Redis key
 * {@code <prefix>:<namespace1>:<namespace2>:…:<key>}. Sets are tracked under
 * {@code <prefix>:idx:<namespace…>} for the {@link #search(List, int, int)} listing.
 */
public class JedisBaseStore implements BaseStore {

    private static final Logger log = LoggerFactory.getLogger(JedisBaseStore.class);

    private final UnifiedJedis jedis;
    private final String keyPrefix;
    private final com.fasterxml.jackson.databind.ObjectMapper mapper =
            new com.fasterxml.jackson.databind.ObjectMapper();

    public JedisBaseStore(UnifiedJedis jedis, String keyPrefix) {
        this.jedis = jedis;
        this.keyPrefix = keyPrefix == null || keyPrefix.isBlank() ? "harness-a2a" : keyPrefix;
    }

    @Override
    public StoreItem get(List<String> namespace, String key) {
        try {
            String redisKey = itemKey(namespace, key);
            String raw = jedis.get(redisKey);
            if (raw == null) {
                return null;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> value = mapper.readValue(raw, Map.class);
            return new StoreItem(key, value);
        } catch (Exception e) {
            log.warn(
                    "JedisBaseStore.get failed for ns={}, key={}: {}",
                    namespace,
                    key,
                    e.getMessage());
            return null;
        }
    }

    @Override
    public void put(List<String> namespace, String key, Map<String, Object> value) {
        try {
            String redisKey = itemKey(namespace, key);
            String idxKey = indexKey(namespace);
            String json = mapper.writeValueAsString(value != null ? value : new HashMap<>());
            jedis.set(redisKey, json);
            jedis.sadd(idxKey, key);
        } catch (Exception e) {
            log.warn(
                    "JedisBaseStore.put failed for ns={}, key={}: {}",
                    namespace,
                    key,
                    e.getMessage());
        }
    }

    @Override
    public List<StoreItem> search(List<String> namespace, int limit, int offset) {
        try {
            String idxKey = indexKey(namespace);
            List<StoreItem> result = new ArrayList<>();
            int skipped = 0;
            for (String key : jedis.smembers(idxKey)) {
                if (skipped++ < offset) {
                    continue;
                }
                StoreItem item = get(namespace, key);
                if (item != null) {
                    result.add(item);
                }
                if (result.size() >= limit) {
                    break;
                }
            }
            return result;
        } catch (Exception e) {
            log.warn("JedisBaseStore.search failed for ns={}: {}", namespace, e.getMessage());
            return List.of();
        }
    }

    @Override
    public void delete(List<String> namespace, String key) {
        try {
            jedis.del(itemKey(namespace, key));
            jedis.srem(indexKey(namespace), key);
        } catch (Exception e) {
            log.warn(
                    "JedisBaseStore.delete failed for ns={}, key={}: {}",
                    namespace,
                    key,
                    e.getMessage());
        }
    }

    private String itemKey(List<String> namespace, String key) {
        StringBuilder sb = new StringBuilder(keyPrefix);
        for (String part : namespace) {
            sb.append(':').append(sanitize(part));
        }
        sb.append(':').append(sanitize(key));
        return sb.toString();
    }

    private String indexKey(List<String> namespace) {
        StringBuilder sb = new StringBuilder(keyPrefix).append(":idx");
        for (String part : namespace) {
            sb.append(':').append(sanitize(part));
        }
        return sb.toString();
    }

    private static String sanitize(String s) {
        if (s == null) {
            return "_";
        }
        return new String(
                s.replace(":", "%3A").getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
    }
}
