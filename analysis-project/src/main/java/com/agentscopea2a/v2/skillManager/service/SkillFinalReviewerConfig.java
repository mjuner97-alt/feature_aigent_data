package com.agentscopea2a.v2.skillManager.service;

import com.agentscopea2a.entity.AiChatRuntimeConfig;
import com.agentscopea2a.mapper.gauss.AiChatRuntimeConfigMapper;
import com.agentscopea2a.v2.config.AiChatRuntimeConfigKeys;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 技能终审人配置 - 从运行时配置表读取当前有效的终审白名单(技能 owner 无权终审自己的提交)。
 *
 * <p>配置值非法(非 JSON 数组/空/含空值)时返回空集,即视为"未启用终审,所有提交无法进入终审"。</p>
 */
@Service
public class SkillFinalReviewerConfig {
    private static final Logger log = LoggerFactory.getLogger(SkillFinalReviewerConfig.class);

    private final AiChatRuntimeConfigMapper mapper;
    private final ObjectMapper objectMapper;

    public SkillFinalReviewerConfig(AiChatRuntimeConfigMapper mapper, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    /**
     * 获取当前有效的终审人 ID 集合(只读)。
     *
     * @return 配置正常的终审人用户 ID 集合;异常时返回空集。
     */
    public Set<String> currentReviewerIds() {
        AiChatRuntimeConfig config;
        try {
            config = mapper.selectByConfigKey(AiChatRuntimeConfigKeys.SKILL_FINAL_REVIEWER_USER_IDS);
        } catch (Exception ignored) {
            log.warn("Unable to load final reviewer whitelist; final review is disabled");
            return Set.of();
        }

        if (config == null) {
            log.warn("Final reviewer whitelist is missing; final review is disabled");
            return Set.of();
        }
        return parseReviewerIds(config.getConfigValue());
    }

    /**
     * 解析 JSON 数组形式的白名单字符串,返回去重后的 ID 集合。
     *
     * @param value 配置字符串(期望是 JSON 字符串数组)
     * @return 规范化后的终审人 ID 集合;不符合约定时返回空集。
     */
    private Set<String> parseReviewerIds(String value) {
        if (value == null || value.isBlank()) {
            log.warn("Final reviewer whitelist is blank; final review is disabled");
            return Set.of();
        }

        final JsonNode reviewerNodes;
        try {
            reviewerNodes = objectMapper.readerFor(JsonNode.class)
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readValue(value);
        } catch (JsonProcessingException ignored) {
            log.warn("Final reviewer whitelist is malformed; final review is disabled");
            return Set.of();
        }
        if (reviewerNodes == null || !reviewerNodes.isArray()) {
            log.warn("Final reviewer whitelist is not a JSON array; final review is disabled");
            return Set.of();
        }

        Set<String> reviewerIds = new LinkedHashSet<>();
        for (JsonNode reviewerNode : reviewerNodes) {
            if (!reviewerNode.isTextual()) {
                log.warn("Final reviewer whitelist contains a non-string value; final review is disabled");
                return Set.of();
            }
            String reviewerId = reviewerNode.textValue();
            if (reviewerId.isBlank()) {
                log.warn("Final reviewer whitelist contains a blank user ID; final review is disabled");
                return Set.of();
            }
            reviewerId = reviewerId.strip();
            reviewerIds.add(reviewerId);
        }
        if (reviewerIds.isEmpty()) {
            log.warn("Final reviewer whitelist is empty; final review is disabled");
            return Set.of();
        }
        return Set.copyOf(reviewerIds);
    }
}
