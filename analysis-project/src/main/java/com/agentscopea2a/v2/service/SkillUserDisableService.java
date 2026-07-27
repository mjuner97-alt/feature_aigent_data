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
package com.agentscopea2a.v2.service;

import com.agentscopea2a.entity.SkillUserDisable;
import com.agentscopea2a.mapper.mysql.SkillUserDisableMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 用户禁用 Service:用户对某个 Skill 进行个人禁用 / 取消禁用(幂等),记录操作历史。
 * 语义对应 spec §12.3.2。
 */
@Service
public class SkillUserDisableService {

    private static final Logger log = LoggerFactory.getLogger(SkillUserDisableService.class);

    private final SkillUserDisableMapper mapper;
    private final SkillService skillService;
    private final SkillOperationHistoryService historyService;

    public SkillUserDisableService(SkillUserDisableMapper mapper,
                                   SkillService skillService,
                                   SkillOperationHistoryService historyService) {
        this.mapper = mapper;
        this.skillService = skillService;
        this.historyService = historyService;
    }

    @Transactional
    public void disable(Long skillId, String userId) {
        skillService.get(skillId); // 校验 Skill 存在
        if (mapper.existsByUserSkill(userId, skillId)) {
            return; // 幂等
        }
        try {
            mapper.insert(SkillUserDisable.builder()
                    .skillId(skillId).userId(userId)
                    .createdAt(LocalDateTime.now()).build());
            historyService.record(skillId, null, userId, "DISABLE", null, null);
        } catch (DuplicateKeyException e) {
            log.debug("concurrent disable race, idempotent: skill={} user={}", skillId, userId);
        }
    }

    @Transactional
    public void enable(Long skillId, String userId) {
        skillService.get(skillId); // 校验 Skill 存在
        mapper.deleteByUserSkill(userId, skillId);
        historyService.record(skillId, null, userId, "ENABLE", null, null);
    }

    public boolean isDisabled(Long skillId, String userId) {
        return mapper.existsByUserSkill(userId, skillId);
    }
}
