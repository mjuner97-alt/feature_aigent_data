package com.agentscopea2a.v2.registry.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Shared validation for registry debugging and agent script execution. */
@Component
public class ScriptParamValidator {
    private final ObjectMapper objectMapper = new ObjectMapper();

    public void validate(String schemaJson, Map<String, Object> params) {
        Map<String, Object> values = params == null ? Map.of() : params;
        List<Map<String, Object>> schema;
        try {
            schema = objectMapper.readValue(schemaJson == null || schemaJson.isBlank() ? "[]" : schemaJson, List.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("PARAM_SCHEMA_VIOLATION: 参数定义不是合法 JSON 数组");
        }
        Map<String, Map<String, Object>> definitions = new java.util.LinkedHashMap<>();
        for (Map<String, Object> item : schema) {
            String name = String.valueOf(item.get("name"));
            if (name == null || name.isBlank() || "null".equals(name)) {
                throw new IllegalArgumentException("PARAM_SCHEMA_VIOLATION: 参数定义缺少 name");
            }
            definitions.put(name, item);
        }
        for (String name : values.keySet()) {
            if (!definitions.containsKey(name)) {
                throw new IllegalArgumentException("PARAM_SCHEMA_VIOLATION: 未声明参数 '" + name + "'");
            }
        }
        for (Map.Entry<String, Map<String, Object>> item : definitions.entrySet()) {
            Object value = values.get(item.getKey());
            if (Boolean.TRUE.equals(item.getValue().get("required")) && value == null) {
                throw new IllegalArgumentException("PARAM_SCHEMA_VIOLATION: 缺少必填参数 '" + item.getKey() + "'");
            }
            if (value != null && !matches(String.valueOf(item.getValue().getOrDefault("type", "string")), value)) {
                throw new IllegalArgumentException("PARAM_SCHEMA_VIOLATION: 参数 '" + item.getKey() + "' 类型不匹配");
            }
        }
    }

    private static boolean matches(String type, Object value) {
        return switch (type) {
            case "string" -> value instanceof String;
            case "int" -> value instanceof Integer || value instanceof Long || value instanceof Short;
            case "boolean" -> value instanceof Boolean;
            case "date" -> value instanceof String text && validDate(text);
            case "string[]" -> value instanceof List<?> list && list.stream().allMatch(String.class::isInstance);
            case "int[]" -> value instanceof List<?> list && list.stream().allMatch(v -> v instanceof Integer || v instanceof Long || v instanceof Short);
            case "date[]" -> value instanceof List<?> list && list.stream().allMatch(v -> v instanceof String text && validDate(text));
            default -> false;
        };
    }

    private static boolean validDate(String text) {
        try { LocalDate.parse(text); return true; } catch (Exception ignored) { return false; }
    }
}
