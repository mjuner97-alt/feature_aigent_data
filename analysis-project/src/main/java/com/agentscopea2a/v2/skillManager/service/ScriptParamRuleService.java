package com.agentscopea2a.v2.skillManager.service;

import com.agentscopea2a.entity.ScriptRegistryEntry;
import com.agentscopea2a.mapper.gauss.ScriptRegistryMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** 解析节点中保存的动态时间配置；普通参数原样透传，不依赖预设规则表。 */
@Service
public class ScriptParamRuleService {

    private final ScriptRegistryMapper scriptRegistryMapper;
    private final ObjectMapper objectMapper;
    private final PeriodWindowResolver periodWindowResolver;
    private final BuildingVersionsResolver buildingVersionsResolver;

    public ScriptParamRuleService(ScriptRegistryMapper scripts, ObjectMapper json) {
        this(scripts, json, new PeriodWindowResolver(120));
    }

    public ScriptParamRuleService(ScriptRegistryMapper scripts, ObjectMapper json, PeriodWindowResolver resolver) {
        this(
            scripts,
            json,
            resolver,
            new BuildingVersionsResolver((com.agentscopea2a.v2.hooks.VersionPlanKnowledgeHook) null)
        );
    }

    @Autowired
    public ScriptParamRuleService(
        ScriptRegistryMapper scripts,
        ObjectMapper json,
        PeriodWindowResolver resolver,
        BuildingVersionsResolver buildingVersionsResolver
    ) {
        this.scriptRegistryMapper = scripts;
        this.objectMapper = json;
        this.periodWindowResolver = resolver;
        this.buildingVersionsResolver = buildingVersionsResolver;
    }

    public Map<String, Object> resolve(String scriptId, String paramsJson, LocalDate dataDate) {
        Map<String, Object> params = parseParams(paramsJson);
        Map<String, String> types = containsDynamic(params) ? loadStrictParamTypes(scriptId, params) : Map.of();
        Map<String, Object> resolved = new LinkedHashMap<>();
        List<String> buildingVersions = null;
        for (var entry : params.entrySet()) {
            if (isBuildingVersions(entry.getValue())) {
                buildingVersionsResolver.validate(entry.getValue(), types.get(entry.getKey()));
                if (buildingVersions == null) buildingVersions = buildingVersionsResolver.resolve();
                resolved.put(entry.getKey(), new java.util.ArrayList<>(buildingVersions));
                continue;
            }
            resolved.put(
                entry.getKey(),
                isDynamic(entry.getValue())
                    ? periodWindowResolver.resolve(entry.getValue(), types.get(entry.getKey()), dataDate)
                    : entry.getValue()
            );
        }
        return resolved;
    }

    /** 显式引用公共参数，仅允许字符串与单元素字符串数组之间转换。 */
    public Map<String, Object> resolve(
        String scriptId,
        String paramsJson,
        LocalDate date,
        Map<String, Object> publicParams
    ) {
        Map<String, Object> values = new LinkedHashMap<>(resolve(scriptId, paramsJson, date));
        boolean hasPublicReference = values.values().stream().anyMatch(ScriptParamRuleService::isPublic);
        boolean maySupplyReportType = publicParams.containsKey("report_type") && !values.containsKey("report_type");
        if (!hasPublicReference && !maySupplyReportType) return values;
        Map<String, String> types = loadStrictParamTypes(scriptId, values);
        if (hasPublicReference) {
            values.replaceAll((name, value) -> {
                if (!isPublic(value)) return value;
                String key = String.valueOf(((Map<?, ?>) value).get("$public"));
                if (!publicParams.containsKey(key)) throw new IllegalStateException(
                    "TeamReportPublicReferenceUnavailable: " + key
                );
                Object actual = publicParams.get(key);
                String type = types.get(name).toLowerCase(java.util.Locale.ROOT);
                if (publicCompatible(type, key)) return isArrayType(type) ? List.of(actual) : actual;
                throw new IllegalStateException("TeamReportPublicTypeIncompatible: " + name + " (" + type + ")");
            });
        }
        // 团队／部门报告的 Python 脚本只要声明 report_type，节点未显式赋值时自动补入。
        // 显式参数仍优先，避免改变已有任务的固定配置。
        if (maySupplyReportType && types.containsKey("report_type")) {
            String type = types.get("report_type").toLowerCase(java.util.Locale.ROOT);
            if (!publicCompatible(type, "report_type")) throw new IllegalStateException(
                "TeamReportPublicTypeIncompatible: report_type (" + type + ")"
            );
            Object actual = publicParams.get("report_type");
            values.put("report_type", isArrayType(type) ? List.of(actual) : actual);
        }
        return values;
    }

    private static boolean publicCompatible(String type, String key) {
        return (
            java.util.Set.of("string", "str", "array", "string[]", "array<string>").contains(type) ||
            ("data_date".equals(key) && java.util.Set.of("date", "date[]", "array<date>").contains(type))
        );
    }

    private static boolean isPublic(Object value) {
        return value instanceof Map<?, ?> map && map.containsKey("$public");
    }

    /** 校验动态配置和参数类型，不在保存时将周期展开成具体月份。 */
    public List<String> validateParams(String scriptId, String paramsJson) {
        try {
            Map<String, Object> params = parseParams(paramsJson);
            Map<String, String> types = containsDynamic(params) ? loadStrictParamTypes(scriptId, params) : Map.of();
            for (var entry : params.entrySet()) {
                if (isPublic(entry.getValue())) {
                    Map<String, String> publicTypes = loadStrictParamTypes(scriptId, params);
                    String type = publicTypes.get(entry.getKey()).toLowerCase(java.util.Locale.ROOT);
                    if (
                        !publicCompatible(type, String.valueOf(((Map<?, ?>) entry.getValue()).get("$public")))
                    ) throw new IllegalStateException("TeamReportPublicTypeIncompatible: " + entry.getKey());
                }
                if (isBuildingVersions(entry.getValue())) buildingVersionsResolver.validate(
                    entry.getValue(),
                    types.get(entry.getKey())
                );
                else if (isDynamic(entry.getValue())) periodWindowResolver.resolve(
                    entry.getValue(),
                    types.get(entry.getKey()),
                    LocalDate.of(2000, 1, 1)
                );
            }
            return List.of();
        } catch (IllegalStateException error) {
            return List.of(error.getMessage());
        }
    }

    public String prepareForSave(String scriptId, String paramsJson) {
        List<String> errors = validateParams(scriptId, paramsJson);
        if (!errors.isEmpty()) throw new IllegalStateException(String.join("; ", errors));
        return paramsJson;
    }

    private Map<String, Object> parseParams(String paramsJson) {
        if (paramsJson == null || paramsJson.isBlank()) return Map.of();
        try {
            Map<String, Object> parsed = objectMapper.readValue(
                paramsJson,
                new TypeReference<Map<String, Object>>() {}
            );
            if (parsed == null) throw new IllegalArgumentException("parameters must be an object");
            validateMarkers(parsed, 0);
            return parsed;
        } catch (IllegalStateException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalStateException("ScriptParamsInvalid: " + error.getMessage(), error);
        }
    }

    private static void validateMarkers(Object value, int depth) {
        if (value instanceof Map<?, ?> map) {
            if (map.containsKey("$public")) {
                if (
                    depth != 1 ||
                    map.size() != 1 ||
                    !java.util.Set.of("report_type", "dev_dept", "stat_group", "data_date").contains(map.get("$public"))
                ) throw new IllegalStateException("TeamReportPublicReferenceInvalid");
            }
            if (map.containsKey("$rule")) throw new IllegalStateException(
                "ScriptParamsInvalid: 旧预设规则已停用，请在页面重新配置动态时间"
            );
            if (map.containsKey("$dynamic") && depth != 1) throw new IllegalStateException(
                "ScriptParamsInvalid: 动态时间配置必须直接绑定参数，不能嵌套在数组或对象内"
            );
            map.values().forEach(child -> validateMarkers(child, depth + 1));
        } else if (value instanceof List<?> list) {
            list.forEach(child -> validateMarkers(child, depth + 1));
        }
    }

    public int getMaxPeriods() {
        return periodWindowResolver.getMaxPeriods();
    }

    static boolean isArrayType(String type) {
        if (type == null) return false;
        String normalized = type.trim().toLowerCase(java.util.Locale.ROOT);
        return (
            normalized.equals("array") ||
            normalized.endsWith("[]") ||
            (normalized.startsWith("array<") && normalized.endsWith(">"))
        );
    }

    public boolean containsDynamic(String paramsJson) {
        return containsDynamic(parseParams(paramsJson));
    }

    private static boolean isDynamic(Object value) {
        return value instanceof Map<?, ?> map && map.containsKey("$dynamic");
    }

    private static boolean isBuildingVersions(Object value) {
        return (
            value instanceof Map<?, ?> outer &&
            outer.get("$dynamic") instanceof Map<?, ?> config &&
            "building_versions".equals(config.get("kind"))
        );
    }

    private static boolean containsDynamic(Map<String, Object> params) {
        return params.values().stream().anyMatch(ScriptParamRuleService::isDynamic);
    }

    static String formatPeriod(String pattern, int year, int month) {
        int quarter = (month - 1) / 3 + 1;
        return pattern
            .replace("{year}", String.valueOf(year))
            .replace("{month:02}", String.format(java.util.Locale.ROOT, "%02d", month))
            .replace("{month}", String.valueOf(month))
            .replace("{quarter}", String.valueOf(quarter));
    }

    private Map<String, String> loadStrictParamTypes(String scriptId, Map<String, Object> params) {
        try {
            ScriptRegistryEntry entry = scriptId == null ? null : scriptRegistryMapper.selectByScriptId(scriptId);
            if (
                entry == null ||
                Integer.valueOf(0).equals(entry.getEnabled()) ||
                entry.getParamsSchema() == null ||
                entry.getParamsSchema().isBlank()
            ) throw new IllegalArgumentException("missing enabled script/schema");
            JsonNode schema = objectMapper.readTree(entry.getParamsSchema());
            if (schema == null || !schema.isArray()) throw new IllegalArgumentException("schema must be an array");
            Map<String, String> types = new LinkedHashMap<>();
            for (JsonNode p : schema) {
                if (
                    !p.isObject() ||
                    !p.path("name").isTextual() ||
                    p.path("name").asText().isBlank() ||
                    !p.path("type").isTextual() ||
                    p.path("type").asText().isBlank()
                ) throw new IllegalArgumentException("invalid parameter definition");
                if (
                    types.putIfAbsent(p.get("name").asText(), p.get("type").asText()) != null
                ) throw new IllegalArgumentException("duplicate parameter definition");
            }
            for (String name : params.keySet())
                if (!types.containsKey(name)) throw new IllegalArgumentException("unknown parameter " + name);
            return types;
        } catch (Exception e) {
            throw new IllegalStateException(
                "ScriptParamsInvalid: dynamic schema validation failed: " + e.getMessage(),
                e
            );
        }
    }
}
