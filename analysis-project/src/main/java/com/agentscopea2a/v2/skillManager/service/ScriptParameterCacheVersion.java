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
            if (params != null && params.isObject()) {
                for (JsonNode value : params) {
                    if (value.isObject() && value.has("$dynamic")) versions.add(value.path("$dynamic").path("v").asText());
                }
            }
            return versions.isEmpty() ? scriptId : scriptId + ":period-window-v" + String.join(",", versions);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("ScriptParamsInvalid: 无法读取参数解析版本", e);
        }
    }
}
