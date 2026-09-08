package com.agentscopea2a.v2.toolrouting;

/** One canonical, administratively governed routing tag. */
public record ToolRoutingTag(
        ToolRoutingTagType tagType,
        String tagName,
        String description,
        boolean enabled) {

    public ToolRoutingTag {
        if (tagType == null) {
            throw new IllegalArgumentException("tagType 不能为空");
        }
        if (tagName == null || tagName.isBlank()) {
            throw new IllegalArgumentException("tagName 不能为空");
        }
        tagName = tagName.trim();
        description = description == null ? "" : description.trim();
    }
}
