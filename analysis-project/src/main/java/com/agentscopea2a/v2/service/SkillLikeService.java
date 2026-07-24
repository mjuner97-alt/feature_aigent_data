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

import com.agentscopea2a.dto.LikeStatus;
import com.agentscopea2a.entity.Skill;
import com.agentscopea2a.entity.SkillLike;
import com.agentscopea2a.mapper.mysql.SkillLikeMapper;
import com.agentscopea2a.mapper.mysql.SkillManageMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 点赞 Service:幂等 toggle + like_count 原子增减。
 *
 * <p>语义:重复点赞返回成功且计数不变(唯一约束 + DuplicateKeyException 兜底并发竞争)。
 * like_count 仅在状态真正变更时 ±1(事务内)。
 */
@Service
public class SkillLikeService {

    private static final Logger log = LoggerFactory.getLogger(SkillLikeService.class);

    private final SkillLikeMapper likeMapper;
    private final SkillManageMapper manageMapper;
    private final SkillService skillService;

    public SkillLikeService(SkillLikeMapper likeMapper, SkillManageMapper manageMapper, SkillService skillService) {
        this.likeMapper = likeMapper;
        this.manageMapper = manageMapper;
        this.skillService = skillService;
    }

    @Transactional
    public LikeStatus like(Long skillId, String userId) {
        assertActive(skillId);
        if (likeMapper.selectByUserSkill(userId, skillId) != null) {
            return new LikeStatus(true, currentCount(skillId));
        }
        try {
            likeMapper.insert(SkillLike.builder()
                    .skillId(skillId).userId(userId).createdAt(LocalDateTime.now()).build());
            manageMapper.incrementLikeCount(skillId);
        } catch (DuplicateKeyException e) {
            log.debug("concurrent like race, treat as idempotent: skill={} user={}", skillId, userId);
        }
        return new LikeStatus(true, currentCount(skillId));
    }

    @Transactional
    public LikeStatus unlike(Long skillId, String userId) {
        assertActive(skillId);
        if (likeMapper.selectByUserSkill(userId, skillId) == null) {
            return new LikeStatus(false, currentCount(skillId));
        }
        likeMapper.deleteByUserSkill(userId, skillId);
        manageMapper.decrementLikeCount(skillId);
        return new LikeStatus(false, currentCount(skillId));
    }

    public LikeStatus getStatus(Long skillId, String userId) {
        boolean liked = likeMapper.selectByUserSkill(userId, skillId) != null;
        return new LikeStatus(liked, currentCount(skillId));
    }

    private void assertActive(Long skillId) {
        Skill s = skillService.get(skillId);
        if (!"ACTIVE".equals(s.getStatus())) {
            throw new IllegalStateException("SkillNotActive: " + skillId);
        }
    }

    private long currentCount(Long skillId) {
        Long c = manageMapper.selectLikeCount(skillId);
        return c == null ? 0L : c;
    }
}
