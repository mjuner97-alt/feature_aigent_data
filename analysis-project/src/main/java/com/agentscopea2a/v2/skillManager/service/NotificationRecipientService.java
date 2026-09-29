/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.agentscopea2a.v2.skillManager.service;

import com.agentscopea2a.v2.auth.entity.DeveloperPlPersonInfo;
import com.agentscopea2a.v2.auth.mapper.DeveloperPlPersonInfoMapper;
import com.agentscopea2a.v2.skillManager.dto.NotificationRecipientDto;
import com.agentscopea2a.v2.skillManager.entity.NotificationConfig;
import com.agentscopea2a.v2.skillManager.entity.NotificationRecipient;
import com.agentscopea2a.v2.skillManager.mapper.NotificationConfigMapper;
import com.agentscopea2a.v2.skillManager.mapper.NotificationRecipientMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 通知收件人业务层:通用通知配置(notification_config)与收件人关系(notification_recipient)
 * 的读写在事务内的统一入口。
 *
 * <p>第一阶段(phase-1)范围:目标类型仅 SKILL_JOB / SKILL_FLOW,渠道固定 EMAIL,
 * 收件人类型固定 TO。
 *
 * <p>注意:{@code notification_config.enabled} 是配置记录启用标记,
 * 不是任务/流程"完成通知开关"——通知开关仍由任务/流程自身配置负责。
 */
@Service
public class NotificationRecipientService {

    private static final Logger log = LoggerFactory.getLogger(NotificationRecipientService.class);

    private final NotificationConfigMapper configMapper;
    private final NotificationRecipientMapper recipientMapper;
    private final DeveloperPlPersonInfoMapper personInfoMapper;

    public NotificationRecipientService(NotificationConfigMapper configMapper,
                                        NotificationRecipientMapper recipientMapper,
                                        DeveloperPlPersonInfoMapper personInfoMapper) {
        this.configMapper = configMapper;
        this.recipientMapper = recipientMapper;
        this.personInfoMapper = personInfoMapper;
    }

    /**
     * 按 (targetType, targetId) 查配置,不存在则插入(幂等);
     * 并发插入冲突由 (target_type, target_id) 唯一索引兜底,冲突后重查。
     */
    public NotificationConfig findOrCreateConfig(String targetType, Long targetId, String createdBy) {
        validateTargetType(targetType);
        NotificationConfig config = configMapper.selectByTarget(targetType, targetId);
        if (config != null) {
            return config;
        }
        NotificationConfig toCreate = NotificationConfig.builder()
                .targetType(targetType)
                .targetId(targetId)
                .enabled(Boolean.TRUE)
                .createdBy(createdBy)
                .build();
        try {
            configMapper.insertConfig(toCreate);
            return toCreate;
        } catch (DuplicateKeyException e) {
            // 并发下另一个事务先插入了同一 (target_type, target_id),重查即可
            NotificationConfig existing = configMapper.selectByTarget(targetType, targetId);
            if (existing == null) {
                throw e;
            }
            log.info("通知配置并发创建冲突,复用已有配置: targetType={}, targetId={}", targetType, targetId);
            return existing;
        }
    }

    /**
     * 事务性全量替换某业务对象的收件人名单:
     * 去重 -> 人员服务过滤失效用户 -> 删除旧关系 -> 批量插入新关系。
     *
     * @return 实际写入的收件人数量(过滤失效用户后)
     */
    @Transactional("gaussCustomerTransactionManager")
    public int replaceRecipients(String targetType, Long targetId, List<String> userIds, String createdBy) {
        return replaceRecipients(targetType, targetId, "DEFAULT", userIds, createdBy);
    }

    /** Replace the recipient list for one trigger source without touching other sources. */
    @Transactional("gaussCustomerTransactionManager")
    public int replaceRecipients(String targetType, Long targetId, String triggerType,
                                 List<String> userIds, String createdBy) {
        String scope = normalizeTriggerType(triggerType);
        // 去空串、去重,保持原顺序
        Set<String> deduped = new LinkedHashSet<>();
        if (userIds != null) {
            for (String userId : userIds) {
                if (userId != null && !userId.isBlank()) {
                    deduped.add(userId.trim());
                }
            }
        }
        // 通过人员服务批量过滤失效用户(空名单不进人员查询)
        List<String> validUserIds = filterValidUserIds(new ArrayList<>(deduped));

        NotificationConfig config = findOrCreateConfig(targetType, targetId, createdBy);

        // 先清后建:删除该配置全部旧关系,再批量插入新关系(同一事务)
        recipientMapper.deleteByConfigIdAndTrigger(config.getId(), scope);
        if (validUserIds.isEmpty()) {
            return 0;
        }
        List<NotificationRecipient> rows = new ArrayList<>(validUserIds.size());
        for (String userId : validUserIds) {
            rows.add(NotificationRecipient.builder()
                    .configId(config.getId())
                    .userId(userId)
                    .triggerType(scope)
                    .recipientType(NotificationRecipient.RECIPIENT_TYPE_TO)
                    .channel(NotificationRecipient.CHANNEL_EMAIL)
                    .enabled(Boolean.TRUE)
                    .build());
        }
        recipientMapper.batchInsert(rows);
        return rows.size();
    }

    /**
     * 按 (targetType, targetId) 查询启用收件人,批量解析显示名称(避免 N+1)。
     * 配置不存在时返回空名单。
     */
    public List<NotificationRecipientDto> listRecipients(String targetType, Long targetId) {
        List<NotificationRecipient> rows = recipientMapper.selectEnabledByTarget(targetType, targetId);
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<String, String> namesByUserId = lookupNames(rows.stream()
                .map(NotificationRecipient::getUserId)
                .filter(Objects::nonNull)
                .distinct()
                .toList());
        List<NotificationRecipientDto> result = new ArrayList<>(rows.size());
        for (NotificationRecipient row : rows) {
            result.add(new NotificationRecipientDto(row.getUserId(), namesByUserId.get(row.getUserId()), row.getEnabled()));
        }
        return result;
    }

    /**
     * 发送侧收件人名单解析(读取优先级,见设计文档「兼容与迁移」):
     * <ul>
     *   <li>{@code notification_config} 存在 → 返回启用收件人 user_id 列表。
     *       列表可能为空:空 = 用户已主动清空名单,调用方直接走创建人/触发人兜底,
     *       不得回退读取旧逗号字段;</li>
     *   <li>{@code notification_config} 不存在 → 返回 {@link Optional#empty()},
     *       调用方兼容读取旧 {@code notify_receivers} 字段并记录兼容日志。</li>
     * </ul>
     *
     * <p>关于 {@code notification_config.enabled} 的过滤决策:本服务创建配置时恒写 TRUE,
     * 且当前没有任何把 enabled 置 FALSE 的更新入口,因此 {@link NotificationRecipientMapper#selectEnabledByTarget}
     * 只过滤 {@code r.enabled},不过滤 {@code c.enabled}(过滤是死代码);
     * 若未来引入"停用整条配置"的入口,需同步在 SQL 中补充 {@code c.enabled = TRUE} 条件。
     */
    public Optional<List<String>> findConfiguredUserIds(String targetType, Long targetId) {
        return findConfiguredUserIds(targetType, targetId, "DEFAULT");
    }

    public Optional<List<String>> findConfiguredUserIds(String targetType, Long targetId, String triggerType) {
        NotificationConfig config = configMapper.selectByTarget(targetType, targetId);
        if (config == null) {
            return Optional.empty();
        }
        List<String> userIds = new ArrayList<>();
        String scope = normalizeTriggerType(triggerType);
        List<NotificationRecipient> rows = recipientMapper.selectEnabledByTargetAndTrigger(targetType, targetId, scope);
        // DEFAULT is the compatibility/common list when no trigger-specific list exists.
        if (rows.isEmpty() && !"DEFAULT".equals(scope)) {
            rows = recipientMapper.selectEnabledByTargetAndTrigger(targetType, targetId, "DEFAULT");
        }
        for (NotificationRecipient recipient : rows) {
            if (recipient.getUserId() != null && !recipient.getUserId().isBlank()) {
                userIds.add(recipient.getUserId());
            }
        }
        return Optional.of(userIds);
    }

    /** Returns all configured recipient groups for settings-page round trips. */
    public Map<String, List<String>> findConfiguredUserIdsByTrigger(String targetType, Long targetId) {
        Map<String, List<String>> result = new java.util.LinkedHashMap<>();
        for (NotificationRecipient row : recipientMapper.selectEnabledByTarget(targetType, targetId)) {
            if (row.getUserId() == null || row.getUserId().isBlank()) continue;
            String scope = row.getTriggerType() == null || row.getTriggerType().isBlank()
                    ? "DEFAULT" : row.getTriggerType();
            result.computeIfAbsent(scope, ignored -> new ArrayList<>()).add(row.getUserId());
        }
        return result;
    }

    /**
     * 批量过滤失效用户:只保留人员表当前版本月份快照中存在的 user_id。
     * 空名单直接返回,不触发人员查询(与 DeveloperPlPersonInfoMapper 的约定一致)。
     */
    private List<String> filterValidUserIds(List<String> userIds) {
        if (userIds.isEmpty()) {
            return userIds;
        }
        Set<String> valid = new HashSet<>();
        List<DeveloperPlPersonInfo> people = personInfoMapper.selectByUserIds(userIds);
        if (people != null) {
            for (DeveloperPlPersonInfo person : people) {
                if (person.getUserId() != null && !person.getUserId().isBlank()) {
                    valid.add(person.getUserId());
                }
                if (person.getLoginUserId() != null && !person.getLoginUserId().isBlank()) {
                    valid.add(person.getLoginUserId());
                }
            }
        }
        List<String> result = new ArrayList<>(userIds.size());
        for (String userId : userIds) {
            if (valid.contains(userId)) {
                result.add(userId);
            } else {
                log.warn("通知收件人过滤失效用户: userId={}", userId);
            }
        }
        return result;
    }

    /** 批量解析 user_id -> 姓名(取人员快照首条姓名,与 ScriptRegistryManageService 同一约定)。 */
    private Map<String, String> lookupNames(List<String> userIds) {
        Map<String, String> namesByUserId = new HashMap<>();
        if (userIds.isEmpty()) {
            return namesByUserId;
        }
        List<DeveloperPlPersonInfo> people = personInfoMapper.selectByUserIds(userIds);
        if (people == null) {
            return namesByUserId;
        }
        for (DeveloperPlPersonInfo person : people) {
            if (person.getName() == null || person.getName().isBlank()) {
                continue;
            }
            if (person.getUserId() != null && !person.getUserId().isBlank()) {
                namesByUserId.putIfAbsent(person.getUserId(), person.getName());
            }
            if (person.getLoginUserId() != null && !person.getLoginUserId().isBlank()) {
                namesByUserId.putIfAbsent(person.getLoginUserId(), person.getName());
            }
        }
        return namesByUserId;
    }

    private void validateTargetType(String targetType) {
        if (!NotificationConfig.TARGET_TYPE_SKILL_JOB.equals(targetType)
                && !NotificationConfig.TARGET_TYPE_SKILL_FLOW.equals(targetType)) {
            throw new IllegalArgumentException("不支持的通知目标类型: " + targetType);
        }
    }

    private String normalizeTriggerType(String triggerType) {
        if (triggerType == null || triggerType.isBlank()) return "DEFAULT";
        String value = triggerType.trim().toUpperCase();
        if (!Set.of("DEFAULT", "AUTO_METRIC", "MANUAL", "CHAT", "EXTERNAL").contains(value)) {
            throw new IllegalArgumentException("不支持的通知触发类型: " + triggerType);
        }
        // 手动执行与对话触发共享同一份最近使用名单；DEFAULT 是兼容且持久化的公共范围。
        if ("MANUAL".equals(value) || "CHAT".equals(value)) return "DEFAULT";
        return value;
    }
}
