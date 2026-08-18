package com.agentscopea2a.v2.presentation;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Short-lived handoff for structured tool results that should not pass through the model. */
@Component
public class PresentationDataReferenceStore {
    private static final Duration TTL = Duration.ofMinutes(10);
    private final Cache<String, DataSet> cache = CacheBuilder.newBuilder()
            .maximumSize(256)
            .expireAfterWrite(TTL.toMinutes(), TimeUnit.MINUTES)
            .build();

    public String put(String providerType, String providerId, List<Map<String, Object>> rows) {
        String ref = "pdr_" + UUID.randomUUID().toString().replace("-", "");
        cache.put(ref, new DataSet(providerType, providerId, List.copyOf(rows)));
        return ref;
    }

    public DataSet get(String ref) {
        if (ref == null || ref.isBlank()) throw new IllegalArgumentException("resultRef 必填");
        DataSet data = cache.getIfPresent(ref.trim());
        if (data == null) throw new IllegalArgumentException("resultRef 不存在或已过期: " + ref);
        return data;
    }

    public record DataSet(String providerType, String providerId, List<Map<String, Object>> rows) {}
}
