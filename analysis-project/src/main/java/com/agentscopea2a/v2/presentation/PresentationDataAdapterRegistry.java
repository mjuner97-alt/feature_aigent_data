package com.agentscopea2a.v2.presentation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/** Generic adapters from registered-provider rows to a presentation variable envelope. */
@Component
public class PresentationDataAdapterRegistry {
    public static final String JSON_ENVELOPE_V1 = "json-envelope-v1";
    public static final String ROWS_V1 = "rows-v1";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_ENVELOPE_LENGTH = 2_000_000;

    public AdaptedData adapt(String adapterId, List<Map<String, Object>> rows,
                             Map<String, Object> params, String fallbackTitle) {
        String normalized = adapterId == null || adapterId.isBlank() ? ROWS_V1 : adapterId.trim();
        return switch (normalized) {
            case JSON_ENVELOPE_V1 -> adaptJsonEnvelope(rows);
            case ROWS_V1, "identity" -> adaptRows(rows, params, fallbackTitle);
            default -> throw new IllegalArgumentException("未知的展示数据适配器: " + adapterId);
        };
    }

    private AdaptedData adaptJsonEnvelope(List<Map<String, Object>> rows) {
        if (rows.size() != 1) {
            throw new IllegalArgumentException(JSON_ENVELOPE_V1
                    + " 要求数据提供者恰好返回一行，实际 " + rows.size() + " 行");
        }
        Map<String, Object> row = rows.get(0);
        ObjectNode variables = parseObject(column(row, "variables_json"), "variables_json");
        ObjectNode summary = parseObject(column(row, "summary_json"), "summary_json");
        return new AdaptedData(variables, summary);
    }

    private AdaptedData adaptRows(List<Map<String, Object>> rows, Map<String, Object> params,
                                  String fallbackTitle) {
        ObjectNode variables = MAPPER.valueToTree(params == null ? Map.of() : params);
        variables.set("records", MAPPER.valueToTree(rows));
        if (!variables.hasNonNull("title")) variables.put("title", fallbackTitle);
        JsonNode summary = rows.isEmpty() ? MAPPER.createObjectNode() : MAPPER.valueToTree(rows.get(0));
        return new AdaptedData(variables, summary);
    }

    private static Object column(Map<String, Object> row, String name) {
        if (row.containsKey(name)) return row.get(name);
        return row.entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase(name))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        JSON_ENVELOPE_V1 + " 缺少列: " + name));
    }

    private static ObjectNode parseObject(Object value, String column) {
        if (value == null) throw new IllegalArgumentException(column + " 不能为空");
        String json = String.valueOf(value);
        if (json.length() > MAX_ENVELOPE_LENGTH) {
            throw new IllegalArgumentException(column + " 超过 2MB 限制");
        }
        try {
            JsonNode parsed = MAPPER.readTree(json);
            if (!parsed.isObject()) throw new IllegalArgumentException(column + " 必须是 JSON 对象");
            return (ObjectNode) parsed;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException(column + " 不是合法 JSON: " + e.getMessage(), e);
        }
    }

    public record AdaptedData(ObjectNode variables, JsonNode summary) {}
}
