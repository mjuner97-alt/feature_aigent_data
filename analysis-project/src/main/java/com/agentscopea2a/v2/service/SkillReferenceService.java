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

import com.agentscopea2a.entity.Skill;
import com.agentscopea2a.entity.SkillReference;
import com.agentscopea2a.mapper.mysql.SkillReferenceMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 引用 Service:用户引用 Skill(幂等)。撑"我使用的 Skill"视图。
 * 语义:用户 A 引用 Skill S -> 写入 (source=S, target=S, creator=A),与 spec §5.6 一致。
 */
@Service
public class SkillReferenceService {

    private static final Logger log = LoggerFactory.getLogger(SkillReferenceService.class);

    private final SkillReferenceMapper refMapper;
    private final SkillService skillService;

    public SkillReferenceService(SkillReferenceMapper refMapper, SkillService skillService) {
        this.refMapper = refMapper;
        this.skillService = skillService;
    }

    @Transactional
    public void reference(Long skillId, String userId) {
        skillService.get(skillId); // 校验 Skill 存在
        if (refMapper.existsByCreatorTarget(userId, skillId)) {
            return; // 幂等
        }
        try {
            refMapper.insert(SkillReference.builder()
                    .sourceSkillId(skillId).targetSkillId(skillId).creator(userId)
                    .createdAt(LocalDateTime.now()).build());
        } catch (DuplicateKeyException e) {
            log.debug("concurrent reference race, idempotent: skill={} user={}", skillId, userId);
        }
    }

    @Transactional
    public void unreference(Long skillId, String userId) {
        refMapper.deleteByCreatorTarget(userId, skillId);
    }

    public List<Long> listMine(String userId) {
        return refMapper.selectSkillIdsByCreator(userId);
    }
}
