package com.agentscopea2a.v2.skillManager.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Set;
import java.util.TreeSet;

/** Keep legacy cache identity; separate results produced by versioned resolvers. */
final class ScriptParameterCacheVersion {
    private ScriptParameterCacheVersion() {}

    public static String build(String scriptId, String paramsJson, ObjectMapper json) {
        if (paramsJson == null || paramsJson.isBlank()) return scriptId;
        try {
            JsonNode params = json.readTree(paramsJson);
            Set<String> versions = new TreeSet<>();
            Set<String> buildingVersions = new TreeSet<>();
            if (params != null && params.isObject()) {
                for (JsonNode value : params) {
                    if (value.isObject() && value.has("$dynamic")) {
                        JsonNode config = value.path("$dynamic");
                        if ("building_versions".equals(config.path("kind").asText())) buildingVersions.add(config.path("v").asText());
                        else versions.add(config.path("v").asText());
                    }
                }
            }
            return scriptId + (versions.isEmpty() ? "" : ":period-window-v" + String.join(",", versions))
                    + (buildingVersions.isEmpty() ? "" : ":building-versions-v" + String.join(",", buildingVersions));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("ScriptParamsInvalid: 无法读取参数解析版本", e);
        }
    }
}
