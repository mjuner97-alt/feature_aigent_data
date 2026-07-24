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

import com.agentscopea2a.dto.SkillListItem;
import com.agentscopea2a.dto.SkillListQuery;
import com.agentscopea2a.entity.Skill;
import com.agentscopea2a.mapper.mysql.SkillLikeMapper;
import com.agentscopea2a.mapper.mysql.SkillManageMapper;
import com.agentscopea2a.mapper.mysql.SkillReferenceMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Skill 管理 Service:CRUD + 列表查询。userId 经请求头 X-User-Id 传入(无 Spring Security)。
 * 列表行批量计算 liked/used 标记;可用性恒为 true(后续计划接入)。
 */
@Service
public class SkillService {

    private final SkillManageMapper skillManageMapper;
    private final SkillLikeMapper likeMapper;
    private final SkillReferenceMapper refMapper;

    public SkillService(SkillManageMapper skillManageMapper,
                        SkillLikeMapper likeMapper,
                        SkillReferenceMapper refMapper) {
        this.skillManageMapper = skillManageMapper;
        this.likeMapper = likeMapper;
        this.refMapper = refMapper;
    }

    public List<SkillListItem> list(SkillListQuery q) {
        List<Skill> skills = skillManageMapper.selectList(q);
        if (skills.isEmpty()) {
            return List.of();
        }
        List<Long> ids = skills.stream().map(Skill::getId).toList();
        Set<Long> likedIds = nullToEmpty(likeMapper.selectLikedSkillIds(q.getUserId(), ids));
        Set<Long> usedIds = nullToEmpty(refMapper.selectUsedSkillIds(q.getUserId(), ids));
        boolean rankVisible = "popular".equals(q.getEffectiveView());
        int rank = q.getEffectiveOffset() + 1;
        List<SkillListItem> items = new ArrayList<>(skills.size());
        for (Skill s : skills) {
            items.add(SkillListItem.of(s, likedIds.contains(s.getId()),
                    usedIds.contains(s.getId()), rankVisible ? rank : null));
            rank++;
        }
        return items;
    }

    private static Set<Long> nullToEmpty(Set<Long> set) {
        return set == null ? Set.of() : set;
    }

    @Transactional
    public Skill create(Skill skill, String ownerUserId) {
        if (skillManageMapper.existsByName(skill.getName())) {
            throw new IllegalStateException("SkillNameConflict: " + skill.getName());
        }
        skill.setOwnerUserId(ownerUserId);
        skill.setStatus("ACTIVE");
        skill.setLikeCount(0L);
        skill.setCreatedAt(LocalDateTime.now());
        skill.setUpdatedAt(LocalDateTime.now());
        skillManageMapper.insert(skill);
        return skillManageMapper.selectById(skill.getId());
    }

    public Skill get(Long id) {
        Skill s = skillManageMapper.selectById(id);
        if (s == null || "DELETED".equals(s.getStatus())) {
            throw new IllegalStateException("SkillNotFound: " + id);
        }
        return s;
    }

    @Transactional
    public Skill update(Long id, Skill patch, String userId) {
        Skill s = get(id);
        if (!s.getOwnerUserId().equals(userId)) {
            throw new IllegalStateException("SkillAccessDenied: " + id);
        }
        if (patch.getName() != null && !patch.getName().equals(s.getName())
                && skillManageMapper.existsByName(patch.getName())) {
            throw new IllegalStateException("SkillNameConflict: " + patch.getName());
        }
        if (patch.getName() != null) s.setName(patch.getName());
        if (patch.getDescription() != null) s.setDescription(patch.getDescription());
        if (patch.getContent() != null) s.setContent(patch.getContent());
        if (patch.getCategory() != null) s.setCategory(patch.getCategory());
        if (patch.getTags() != null) s.setTags(patch.getTags());
        s.setUpdatedAt(LocalDateTime.now());
        skillManageMapper.update(s);
        return skillManageMapper.selectById(id);
    }

    @Transactional
    public void delete(Long id, String userId) {
        Skill s = get(id);
        if (!s.getOwnerUserId().equals(userId)) {
            throw new IllegalStateException("SkillAccessDenied: " + id);
        }
        skillManageMapper.softDelete(id);
    }
}
