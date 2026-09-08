package com.agentscopea2a.v2.toolrouting;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;

/** Routing summary for one SQL, API, or Python script. */
public record ToolRoutingMetadata(
        String toolId,
        ToolRoutingToolType toolType,
        String description,
        List<String> topicTags,
        List<String> metricTags,
        List<String> dimensionTags,
        int priority,
        boolean enabled,
        LocalDateTime updatedAt) {

    public ToolRoutingMetadata {
        if (toolId == null || toolId.isBlank()) {
            throw new IllegalArgumentException("toolId 不能为空");
        }
        if (toolType == null) {
            throw new IllegalArgumentException("toolType 不能为空");
        }
        description = description == null ? "" : description;
        topicTags = normalizeTags(topicTags);
        metricTags = normalizeTags(metricTags);
        dimensionTags = normalizeTags(dimensionTags);
    }

    private static List<String> normalizeTags(List<String> tags) {
        if (tags == null || tags.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String tag : tags) {
            if (tag != null && !tag.trim().isEmpty()) {
                normalized.add(tag.trim());
            }
        }
        return List.copyOf(normalized);
    }
}
