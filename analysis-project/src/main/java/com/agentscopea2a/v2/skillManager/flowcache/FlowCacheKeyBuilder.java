package com.agentscopea2a.v2.skillManager.flowcache;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;

/**
 * 缓存 key 构建器:把五个维度规范化成一个稳定的 SHA-256 十六进制串。
 *
 * <p>五个维度:
 * <ol>
 *   <li>nodeId —— 节点 ID</li>
 *   <li>nodeVersion —— 节点版本(配置改了,旧缓存自然失效)</li>
 *   <li>input —— 节点输入</li>
 *   <li>parameters —— 节点参数(对象字段按 key 排序后再序列化,消除 Map 无序带来的假 miss)</li>
 *   <li>businessDate —— 业务日期(跑批语义:同一天同输入才算同一份结果)</li>
 * </ol>
 *
 * <p>注意:input 目前不做键序规范化,直接按 Jackson 默认顺序序列化。
 * 调用方若传入 Map,同样的语义可能产生不同 key(假 miss,只损失命中率不出错);
 * 接入 FlowExecutionService 时若 input 是 Map,应改为也过 {@link #canonical}。
 */
public final class FlowCacheKeyBuilder {
    private final ObjectMapper mapper;

    public FlowCacheKeyBuilder(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 构建缓存 key。
     *
     * @param nodeId       节点 ID
     * @param nodeVersion  节点版本
     * @param input        节点输入(建议传稳定序列化的对象或 String)
     * @param parameters   节点参数(对象字段会递归按键名排序,顺序无关)
     * @param businessDate 业务日期
     * @return 64 位小写十六进制 SHA-256,即磁盘文件名与 skill_flow_node_cache.cache_key
     */
    public String build(Object nodeId, Object nodeVersion, Object input, Object parameters, LocalDate businessDate) {
        ObjectNode root = mapper.createObjectNode();
        root.set("business_date", mapper.valueToTree(businessDate));
        root.set("input", mapper.valueToTree(input));
        root.set("node_id", mapper.valueToTree(nodeId));
        root.set("node_version", mapper.valueToTree(nodeVersion));
        root.set("parameters", canonical(mapper.valueToTree(parameters)));
        try {
            return sha256(mapper.writeValueAsBytes(root));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Unable to serialize cache key", e);
        }
    }

    /**
     * 递归规范化:对象字段按键名排序后重建,数组保持原顺序(顺序本身有语义)。
     */
    private JsonNode canonical(JsonNode node) {
        if (node == null || node.isValueNode()) return node;
        if (node.isArray()) {
            var array = mapper.createArrayNode();
            node.forEach(v -> array.add(canonical(v)));
            return array;
        }
        ObjectNode object = mapper.createObjectNode();
        var names = new ArrayList<String>();
        node.fieldNames().forEachRemaining(names::add);
        Collections.sort(names);
        for (String name : names) object.set(name, canonical(node.get(name)));
        return object;
    }

    private static String sha256(byte[] value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
