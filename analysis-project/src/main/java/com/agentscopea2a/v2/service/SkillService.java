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
import com.agentscopea2a.entity.SkillPublish;
import com.agentscopea2a.mapper.mysql.SkillLikeMapper;
import com.agentscopea2a.mapper.mysql.SkillManageMapper;
import com.agentscopea2a.mapper.mysql.SkillPublishMapper;
import com.agentscopea2a.mapper.mysql.SkillReferenceMapper;
import com.agentscopea2a.mapper.mysql.SkillUserDisableMapper;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Skill 管理 Service:CRUD + 列表查询。userId 经请求头 X-User-Id 传入(无 Spring Security)。
 * 列表行批量计算 liked/used/disabled 标记;available = used && !disabled。
 */
@Service
public class SkillService {

    private final SkillManageMapper skillManageMapper;
    private final SkillLikeMapper likeMapper;
    private final SkillReferenceMapper refMapper;
    private final SkillUserDisableMapper userDisableMapper;
    private final SkillPublishService publishService;
    private final SkillPublishMapper publishMapper;

    public SkillService(SkillManageMapper skillManageMapper,
                        SkillLikeMapper likeMapper,
                        SkillReferenceMapper refMapper,
                        SkillUserDisableMapper userDisableMapper,
                        @Lazy SkillPublishService publishService,
                        SkillPublishMapper publishMapper) {
        this.skillManageMapper = skillManageMapper;
        this.likeMapper = likeMapper;
        this.refMapper = refMapper;
        this.userDisableMapper = userDisableMapper;
        this.publishService = publishService;
        this.publishMapper = publishMapper;
    }

    public List<SkillListItem> list(SkillListQuery q) {
        List<Skill> skills = skillManageMapper.selectList(q);
        if (skills.isEmpty()) {
            return List.of();
        }
        List<Long> ids = skills.stream().map(Skill::getId).toList();
        Set<Long> likedIds = nullToEmpty(likeMapper.selectLikedSkillIds(q.getUserId(), ids));
        Set<Long> usedIds = nullToEmpty(refMapper.selectUsedSkillIds(q.getUserId(), ids));
        Set<Long> disabledIds = nullToEmpty(userDisableMapper.selectDisabledSkillIds(q.getUserId(), ids));
        List<SkillPublish> approved = publishMapper.selectApprovedBySkillIds(ids);
        java.util.Map<Long, String> skillDimension = new java.util.HashMap<>();
        if (approved != null) {
            for (SkillPublish p : approved) {
                skillDimension.putIfAbsent(p.getSkillId(), p.getTargetType());
            }
        }
        boolean rankVisible = "popular".equals(q.getEffectiveView());
        int rank = q.getEffectiveOffset() + 1;
        List<SkillListItem> items = new ArrayList<>(skills.size());
        for (Skill s : skills) {
            boolean used = usedIds.contains(s.getId());
            boolean disabled = disabledIds.contains(s.getId());
            boolean available = used && !disabled;
            String dim = skillDimension.getOrDefault(s.getId(), "PERSONAL");
            items.add(SkillListItem.of(s, likedIds.contains(s.getId()),
                    used, available, disabled, rankVisible ? rank : null, dim));
            rank++;
        }
        if (q.getDimension() != null && !q.getDimension().isEmpty()) {
            String wantDim = q.getDimension();
            items = items.stream().filter(it -> it.dimension().equals(wantDim)).toList();
        }
        return items;
    }

    /** 查询全部 ACTIVE Skill 的去重 tag 列表。 */
    public List<String> getAllTags() {
        return skillManageMapper.selectAllTags();
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
        if (publishService.hasApproved(id)) {
            throw new IllegalStateException("SkillUpdateRequiresDraft: " + id);
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
