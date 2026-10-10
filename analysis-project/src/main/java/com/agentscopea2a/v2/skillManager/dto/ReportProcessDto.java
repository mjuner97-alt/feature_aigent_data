package com.agentscopea2a.v2.skillManager.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDateTime;

public record ReportProcessDto(
        Long id, String code, String name, String description,
        JsonNode nodes, JsonNode reportOutline, Boolean enabled,
        Long copiedFromId, String copiedFromName, String createdBy,
        LocalDateTime createdAt, LocalDateTime updatedAt) {

    public record SaveRequest(String code, String name, String description,
                              JsonNode nodes, JsonNode reportOutline, Boolean enabled) {}

    public record CopyRequest(String name) {}
    public record EnabledRequest(boolean enabled) {}
}

