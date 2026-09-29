package com.agentscopea2a.v2.skillManager.service;

import com.agentscopea2a.entity.ScriptRegistryEntry;
import com.agentscopea2a.mapper.gauss.ScriptRegistryMapper;
import com.agentscopea2a.v2.skillManager.entity.ScriptParamRule;
import com.agentscopea2a.v2.skillManager.mapper.ScriptParamRuleMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 脚本参数取值规则服务:把 {@code scriptParamsJson} 中的规则标记解析成真实参数值.
 *
 * <p>Python 脚本节点的参数值可写成 {@code {"$rule": "rule_key"}} 标记, 执行时按
 * {@code script_param_rule} 表的周期定义 (period_unit + format_pattern + 偏移窗口)
 * 从数据日期推导出真实值, 免去每次发版手工改流程定义. 普通字面值原样透传.
 *
 * <p>类型适配: 若脚本 {@code params_schema} 声明某参数为 {@code array}, 而命中的是
 * single 规则, 解析结果包装成单元素数组 (数组参数允许选单值规则); 反之 array 规则
 * 配单值参数属于定义错误, 由 {@link #checkRuleRefs} 在流程定义校验时拒绝.
 */
@Service
public class ScriptParamRuleService {

    private static final Logger log = LoggerFactory.getLogger(ScriptParamRuleService.class);

    /** 参数值中的规则标记 key: {"$rule": "rule_key"} */
    static final String RULE_MARKER = "$rule";

    private final ScriptParamRuleMapper ruleMapper;
    private final ScriptRegistryMapper scriptRegistryMapper;
    private final ObjectMapper objectMapper;

    public ScriptParamRuleService(ScriptParamRuleMapper ruleMapper,
                                  ScriptRegistryMapper scriptRegistryMapper,
                                  ObjectMapper objectMapper) {
        this.ruleMapper = ruleMapper;
        this.scriptRegistryMapper = scriptRegistryMapper;
        this.objectMapper = objectMapper;
    }

    /**
     * 解析脚本参数:规则标记替换为真实值, 其余字面值原样保留.
     *
     * @param scriptId         脚本注册 ID (用于读 params_schema 判断参数数组类型; 可为 null)
     * @param scriptParamsJson 执行快照里的参数 JSON (允许 null/空串)
     * @param dataDate         锚点日期 = 流程数据日期 (调用方兜底当天)
     * @return 解析后的参数 (入参为空时返回空 Map)
     * @throws IllegalStateException ScriptParamsInvalid (JSON 非法/标记格式错误)
     *                               或 ParamRuleUnavailable (规则不存在/已停用)
     */
    public Map<String, Object> resolve(String scriptId, String scriptParamsJson, LocalDate dataDate) {
        Map<String, Object> raw = parseParams(scriptParamsJson);
        Map<String, String> paramTypes = loadParamTypes(scriptId);
        Map<String, Object> resolved = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : raw.entrySet()) {
            String ruleKey = extractRuleKey(entry.getKey(), entry.getValue());
            if (ruleKey == null) {
                resolved.put(entry.getKey(), entry.getValue());
                continue;
            }
            ScriptParamRule rule = requireRule(ruleKey);
            Object value = computeValue(rule, dataDate);
            // 数组参数 + 单值规则: 包装成单元素数组
            if (value instanceof String && ScriptParamRule.VALUE_TYPE_ARRAY.equals(paramTypes.get(entry.getKey()))) {
                value = List.of(value);
            }
            resolved.put(entry.getKey(), value);
        }
        return resolved;
    }

    /**
     * 流程定义校验:检查参数里的规则引用 (规则存在、类型与参数声明兼容).
     * 返回错误码列表 (空列表 = 通过); JSON 非法不在此报 (定义校验已有 MalformedScriptParamsJson).
     */
    public List<String> checkRuleRefs(String scriptId, String scriptParamsJson) {
        Map<String, Object> raw;
        Map<String, String> paramTypes;
        try {
            raw = parseParams(scriptParamsJson);
            paramTypes = loadParamTypes(scriptId);
        } catch (IllegalStateException e) {
            return List.of(); // JSON/标记格式问题由既有校验上报, 避免重复报错
        }
        List<String> errors = new ArrayList<>();
        for (Map.Entry<String, Object> entry : raw.entrySet()) {
            String ruleKey = extractRuleKey(entry.getKey(), entry.getValue());
            if (ruleKey == null) continue;
            ScriptParamRule rule = ruleMapper.selectByRuleKey(ruleKey);
            if (rule == null) {
                errors.add("ParamRuleUnavailable: " + ruleKey);
                continue;
            }
            boolean arrayParam = ScriptParamRule.VALUE_TYPE_ARRAY.equals(paramTypes.get(entry.getKey()));
            if (ScriptParamRule.VALUE_TYPE_ARRAY.equals(rule.getValueType()) && !arrayParam) {
                errors.add("ParamRuleTypeMismatch: " + entry.getKey() + " <- " + ruleKey);
            }
        }
        return errors;
    }

    // ------------------------------------------------------------------
    // 内部实现
    // ------------------------------------------------------------------

    /** 参数 JSON 解析;空串视为空参数;非法 JSON 抛 ScriptParamsInvalid. */
    private Map<String, Object> parseParams(String scriptParamsJson) {
        if (scriptParamsJson == null || scriptParamsJson.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(scriptParamsJson, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            throw new IllegalStateException("ScriptParamsInvalid: " + e.getMessage());
        }
    }

    /**
     * 取值若是规则标记 ({"$rule": 非空字符串} 形式的 Map) 返回 rule_key, 否则返回 null.
     * 带 $rule key 但值不是非空字符串视为标记格式错误.
     */
    private String extractRuleKey(String paramName, Object value) {
        if (!(value instanceof Map<?, ?> map) || !map.containsKey(RULE_MARKER)) return null;
        Object marker = map.get(RULE_MARKER);
        if (marker instanceof String key && !key.isBlank()) return key;
        throw new IllegalStateException("ScriptParamsInvalid: 参数 '" + paramName + "' 的 $rule 标记必须是非空字符串");
    }

    /** 查启用规则;不存在/已停用抛 ParamRuleUnavailable. */
    private ScriptParamRule requireRule(String ruleKey) {
        ScriptParamRule rule = ruleMapper.selectByRuleKey(ruleKey);
        if (rule == null) {
            throw new IllegalStateException("ParamRuleUnavailable: " + ruleKey);
        }
        return rule;
    }

    /**
     * 按周期定义计算规则值:遍历偏移窗口逐周期格式化;
     * single 取窗口首值,array 按 sortOrder 排序后返回列表.
     */
    private Object computeValue(ScriptParamRule rule, LocalDate dataDate) {
        List<String> values = new ArrayList<>();
        for (int offset = rule.getOffsetStart(); offset <= rule.getOffsetEnd(); offset++) {
            int year = dataDate.getYear();
            int month = dataDate.getMonthValue();
            switch (rule.getPeriodUnit()) {
                case ScriptParamRule.UNIT_MONTH -> {
                    int index = year * 12 + (month - 1) + offset;
                    year = index / 12;
                    month = index % 12 + 1;
                }
                case ScriptParamRule.UNIT_QUARTER -> {
                    int quarter = (month - 1) / 3 + 1;
                    int index = year * 4 + (quarter - 1) + offset;
                    year = index / 4;
                    month = (index % 4) * 3 + 1; // 目标季度 (index%4 + 1) 的首月
                }
                case ScriptParamRule.UNIT_YEAR -> year += offset;
                default -> throw new IllegalStateException(
                        "ParamRuleUnitInvalid: " + rule.getRuleKey() + " unit=" + rule.getPeriodUnit());
            }
            values.add(formatPeriod(rule.getFormatPattern(), year, month));
        }
        if (ScriptParamRule.VALUE_TYPE_ARRAY.equals(rule.getValueType())) {
            if ("desc".equals(rule.getSortOrder())) Collections.reverse(values);
            return values;
        }
        return values.isEmpty() ? "" : values.get(0);
    }

    /** 格式化一个周期:占位符 {year} {month:02} {month} {quarter}, 其余文本原样. */
    static String formatPeriod(String pattern, int year, int month) {
        int quarter = (month - 1) / 3 + 1;
        return pattern
                .replace("{year}", String.valueOf(year))
                .replace("{month:02}", String.format("%02d", month))
                .replace("{month}", String.valueOf(month))
                .replace("{quarter}", String.valueOf(quarter));
    }

    /**
     * 读脚本的参数类型表 (参数名 -> params_schema 里的 type, 如 string/array).
     * 脚本不存在/schema 为空/解析失败都返回空 Map (不做类型适配, 保持旧行为).
     */
    private Map<String, String> loadParamTypes(String scriptId) {
        if (scriptId == null || scriptId.isBlank()) return Map.of();
        try {
            ScriptRegistryEntry entry = scriptRegistryMapper.selectByScriptId(scriptId);
            if (entry == null || entry.getParamsSchema() == null || entry.getParamsSchema().isBlank()) {
                return Map.of();
            }
            Map<String, String> types = new LinkedHashMap<>();
            JsonNode schema = objectMapper.readTree(entry.getParamsSchema());
            if (schema.isArray()) {
                for (JsonNode param : schema) {
                    if (param.hasNonNull("name") && param.hasNonNull("type")) {
                        types.put(param.get("name").asText(), param.get("type").asText());
                    }
                }
            }
            return types;
        } catch (Exception e) {
            log.warn("script params_schema 解析失败, 规则值不做数组包装: scriptId={} err={}", scriptId, e.getMessage());
            return Map.of();
        }
    }
}
