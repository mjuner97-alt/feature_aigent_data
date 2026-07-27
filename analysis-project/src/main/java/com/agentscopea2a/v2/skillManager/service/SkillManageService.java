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

import com.agentscopea2a.v2.exception.DraftAlreadyPendingException;
import com.agentscopea2a.v2.exception.DraftNotFoundException;
import com.agentscopea2a.v2.exception.NotApproverException;
import com.agentscopea2a.v2.exception.PublishAlreadyApprovedException;
import com.agentscopea2a.v2.skillManager.dto.LikeStatus;
import com.agentscopea2a.v2.skillManager.dto.SkillListItem;
import com.agentscopea2a.v2.skillManager.dto.SkillListQuery;
import com.agentscopea2a.v2.skillManager.entity.Skill;
import com.agentscopea2a.v2.skillManager.entity.SkillApproval;
import com.agentscopea2a.v2.skillManager.entity.SkillDraft;
import com.agentscopea2a.v2.skillManager.entity.SkillLike;
import com.agentscopea2a.v2.skillManager.entity.SkillOperationHistory;
import com.agentscopea2a.v2.skillManager.entity.SkillPublish;
import com.agentscopea2a.v2.skillManager.entity.SkillReference;
import com.agentscopea2a.v2.skillManager.entity.SkillUserDisable;
import com.agentscopea2a.v2.skillManager.entity.SkillVersionHistory;
import com.agentscopea2a.v2.skillManager.mapper.SkillApprovalMapper;
import com.agentscopea2a.v2.skillManager.mapper.SkillDraftMapper;
import com.agentscopea2a.v2.skillManager.mapper.SkillLikeMapper;
import com.agentscopea2a.v2.skillManager.mapper.SkillManageMapper;
import com.agentscopea2a.v2.skillManager.mapper.SkillOperationHistoryMapper;
import com.agentscopea2a.v2.skillManager.mapper.SkillPublishMapper;
import com.agentscopea2a.v2.skillManager.mapper.SkillReferenceMapper;
import com.agentscopea2a.v2.skillManager.mapper.SkillUserDisableMapper;
import com.agentscopea2a.v2.skillManager.mapper.SkillVersionHistoryMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Skill 管理 Service(合并版)。包含全部 Skill 相关业务逻辑:
 * CRUD + 列表查询、点赞、引用、用户禁用、发布审批、变更草稿、版本历史、操作历史。
 *
 * <p>userId 经请求头 X-User-Id 传入(无 Spring Security)。
 * 合并后所有 Mapper 直接注入本类,通过私有方法互相调用,消除了原先
 * SkillService 与 SkillPublishService 之间的循环依赖(@Lazy)。
 *
 * <p>列表行批量计算 liked/used/disabled 标记;available = used && !disabled。
 * 发布流程:Skill 所有者提交发布申请 -> 确定组织审批人 -> 审批人通过或退回。
 * 全程留痕:每次状态流转写 {@link SkillApproval} 与操作历史。
 */
@Service
public class SkillManageService {

    private static final Logger log = LoggerFactory.getLogger(SkillManageService.class);

    private final SkillManageMapper skillManageMapper;
    private final SkillLikeMapper likeMapper;
    private final SkillReferenceMapper refMapper;
    private final SkillUserDisableMapper userDisableMapper;
    private final SkillPublishMapper publishMapper;
    private final SkillApprovalMapper approvalMapper;
    private final SkillDraftMapper draftMapper;
    private final SkillOperationHistoryMapper operationHistoryMapper;
    private final SkillVersionHistoryMapper versionHistoryMapper;
    private final MockOrgService mockOrgService;

    public SkillManageService(SkillManageMapper skillManageMapper,
                              SkillLikeMapper likeMapper,
                              SkillReferenceMapper refMapper,
                              SkillUserDisableMapper userDisableMapper,
                              SkillPublishMapper publishMapper,
                              SkillApprovalMapper approvalMapper,
                              SkillDraftMapper draftMapper,
                              SkillOperationHistoryMapper operationHistoryMapper,
                              SkillVersionHistoryMapper versionHistoryMapper,
                              MockOrgService mockOrgService) {
        this.skillManageMapper = skillManageMapper;
        this.likeMapper = likeMapper;
        this.refMapper = refMapper;
        this.userDisableMapper = userDisableMapper;
        this.publishMapper = publishMapper;
        this.approvalMapper = approvalMapper;
        this.draftMapper = draftMapper;
        this.operationHistoryMapper = operationHistoryMapper;
        this.versionHistoryMapper = versionHistoryMapper;
        this.mockOrgService = mockOrgService;
    }

    // ==================== Skill CRUD + 列表 ====================

    /**
     * 列表查询:支持视图/排序/筛选/分页,批量计算 liked/used/disabled 标记。
     * dimension 过滤已移至前端展示层,避免破坏 SQL 分页(LIMIT/OFFSET)。
     */
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
        Map<Long, String> skillDimension = new HashMap<>();
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
        return items;
    }

    /** 查询全部 ACTIVE Skill 的去重 tag 列表。 */
    public List<String> getAllTags() {
        return skillManageMapper.selectAllTags();
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
        if (hasPendingPublish(id)) {
            throw new IllegalStateException("SkillPendingApproval: " + id);
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
        if (hasPendingPublish(id)) {
            throw new IllegalStateException("SkillPendingApproval: " + id);
        }
        skillManageMapper.softDelete(id);
    }

    // ==================== 版本历史 ====================

    /** 指定 Skill 的版本历史。 */
    public List<SkillVersionHistory> selectVersionsBySkillId(Long skillId) {
        return versionHistoryMapper.selectBySkillId(skillId);
    }

    /**
     * 存旧版本快照到版本历史(供草稿审批通过时调用)。
     */
    private void saveVersion(Skill skill, String editedBy, String editReason) {
        Integer maxVersion = versionHistoryMapper.selectMaxVersion(skill.getId());
        int nextVersion = maxVersion == null ? 1 : maxVersion + 1;
        versionHistoryMapper.insert(SkillVersionHistory.builder()
                .skillId(skill.getId())
                .version(nextVersion)
                .name(skill.getName())
                .description(skill.getDescription())
                .content(skill.getContent())
                .category(skill.getCategory())
                .tags(skill.getTags())
                .editedBy(editedBy)
                .editReason(editReason)
                .createdAt(LocalDateTime.now())
                .build());
    }

    // ==================== 点赞(幂等 toggle + like_count 原子增减) ====================

    @Transactional
    public LikeStatus like(Long skillId, String userId) {
        assertActive(skillId);
        if (likeMapper.selectByUserSkill(userId, skillId) != null) {
            return new LikeStatus(true, currentLikeCount(skillId));
        }
        try {
            likeMapper.insert(SkillLike.builder()
                    .skillId(skillId).userId(userId).createdAt(LocalDateTime.now()).build());
            skillManageMapper.incrementLikeCount(skillId);
        } catch (DuplicateKeyException e) {
            log.debug("concurrent like race, treat as idempotent: skill={} user={}", skillId, userId);
        }
        return new LikeStatus(true, currentLikeCount(skillId));
    }

    @Transactional
    public LikeStatus unlike(Long skillId, String userId) {
        assertActive(skillId);
        if (likeMapper.selectByUserSkill(userId, skillId) == null) {
            return new LikeStatus(false, currentLikeCount(skillId));
        }
        likeMapper.deleteByUserSkill(userId, skillId);
        skillManageMapper.decrementLikeCount(skillId);
        return new LikeStatus(false, currentLikeCount(skillId));
    }

    public LikeStatus getLikeStatus(Long skillId, String userId) {
        boolean liked = likeMapper.selectByUserSkill(userId, skillId) != null;
        return new LikeStatus(liked, currentLikeCount(skillId));
    }

    // ==================== 引用(幂等) ====================

    @Transactional
    public void reference(Long skillId, String userId) {
        get(skillId); // 校验 Skill 存在
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

    public List<Long> listMyReferences(String userId) {
        return refMapper.selectSkillIdsByCreator(userId);
    }

    public List<String> listReferencers(Long skillId) {
        return refMapper.selectReferencersBySkillId(skillId);
    }

    // ==================== 用户禁用(幂等) ====================

    @Transactional
    public void disable(Long skillId, String userId) {
        get(skillId); // 校验 Skill 存在
        if (userDisableMapper.existsByUserSkill(userId, skillId)) {
            return; // 幂等
        }
        try {
            userDisableMapper.insert(SkillUserDisable.builder()
                    .skillId(skillId).userId(userId)
                    .createdAt(LocalDateTime.now()).build());
            recordOperation(skillId, null, userId, "DISABLE", null, null);
        } catch (DuplicateKeyException e) {
            log.debug("concurrent disable race, idempotent: skill={} user={}", skillId, userId);
        }
    }

    @Transactional
    public void enable(Long skillId, String userId) {
        get(skillId); // 校验 Skill 存在
        userDisableMapper.deleteByUserSkill(userId, skillId);
        recordOperation(skillId, null, userId, "ENABLE", null, null);
    }

    public boolean isDisabled(Long skillId, String userId) {
        return userDisableMapper.existsByUserSkill(userId, skillId);
    }

    // ==================== 发布审批 ====================

    /**
     * 提交发布申请。仅 Skill 所有者可提交。
     *
     * @return 新建的发布记录 id
     */
    @Transactional
    public Long submitPublish(Long skillId, String targetType, String targetId, String targetName, String userId) {
        Skill s = get(skillId); // 不存在抛 IllegalStateException
        if (!s.getOwnerUserId().equals(userId)) {
            throw new IllegalStateException("SkillAccessDenied: " + skillId);
        }
        String approverId = mockOrgService.getApprover(targetType, targetId);
        if (approverId == null) {
            throw new IllegalStateException("NoApproverConfigured: " + targetType + ":" + targetId);
        }
        SkillPublish publish = SkillPublish.builder()
                .skillId(skillId)
                .targetType(targetType)
                .targetId(targetId)
                .targetName(targetName)
                .status("PENDING")
                .submitter(userId)
                .currentApproverUserId(approverId)
                .build();
        publishMapper.insert(publish);
        Long publishId = publish.getId();

        approvalMapper.insert(SkillApproval.builder()
                .publishId(publishId)
                .draftId(null)
                .action("SUBMIT")
                .operator(userId)
                .comment(null)
                .versionSnapshot(0)
                .build());
        recordOperation(skillId, publishId, userId, "PUBLISH_SUBMIT", null, null);
        log.info("publish submitted: skill={} publish={} submitter={} approver={}",
                skillId, publishId, userId, approverId);
        return publishId;
    }

    /**
     * 审批通过。仅当前审批人可操作,且发布记录须处于 PENDING 状态。
     */
    @Transactional
    public void approvePublish(Long publishId, String approverId, String comment) {
        SkillPublish p = publishMapper.selectById(publishId);
        if (p == null) {
            throw new IllegalStateException("PublishNotFound: " + publishId);
        }
        if (!"PENDING".equals(p.getStatus())) {
            throw new PublishAlreadyApprovedException("PublishAlreadyApproved: " + publishId);
        }
        if (!approverId.equals(p.getCurrentApproverUserId())) {
            throw new NotApproverException("NotApprover: " + approverId);
        }
        publishMapper.updateStatus(publishId, "APPROVED", approverId, comment);
        approvalMapper.insert(SkillApproval.builder()
                .publishId(publishId)
                .draftId(null)
                .action("APPROVE")
                .operator(approverId)
                .comment(comment)
                .versionSnapshot(0)
                .build());
        recordOperation(p.getSkillId(), publishId, approverId, "PUBLISH_APPROVE", null, null);
        log.info("publish approved: publish={} approver={}", publishId, approverId);
    }

    /**
     * 审批退回。仅当前审批人可操作,且发布记录须处于 PENDING 状态。
     */
    @Transactional
    public void rejectPublish(Long publishId, String approverId, String comment) {
        SkillPublish p = publishMapper.selectById(publishId);
        if (p == null) {
            throw new IllegalStateException("PublishNotFound: " + publishId);
        }
        if (!"PENDING".equals(p.getStatus())) {
            throw new PublishAlreadyApprovedException("PublishAlreadyApproved: " + publishId);
        }
        if (!approverId.equals(p.getCurrentApproverUserId())) {
            throw new NotApproverException("NotApprover: " + approverId);
        }
        publishMapper.updateStatus(publishId, "REJECTED", approverId, comment);
        approvalMapper.insert(SkillApproval.builder()
                .publishId(publishId)
                .draftId(null)
                .action("REJECT")
                .operator(approverId)
                .comment(comment)
                .versionSnapshot(0)
                .build());
        recordOperation(p.getSkillId(), publishId, approverId, "PUBLISH_REJECT", null, null);
        log.info("publish rejected: publish={} approver={}", publishId, approverId);
    }

    /** 返回指定审批人名下的待审发布列表。 */
    public List<SkillPublish> pendingPublishesForApprover(String userId) {
        return publishMapper.selectPendingByApprover(userId);
    }

    /** 返回指定审批人已处理过的发布列表(APPROVED/REJECTED)。 */
    public List<SkillPublish> historyPublishesForApprover(String userId) {
        return publishMapper.selectHistoryByApprover(userId);
    }

    /** 返回指定发布记录的审批历史(按时间正序)。 */
    public List<SkillApproval> publishApprovalHistory(Long publishId) {
        return approvalMapper.selectByPublishId(publishId);
    }

    /** 返回指定 Skill 的全部发布记录(含 APPROVED 和 PENDING),按创建时间倒序。 */
    public List<SkillPublish> listPublishesBySkillId(Long skillId) {
        return publishMapper.selectBySkillId(skillId);
    }

    /** 判断指定 Skill 是否存在审批中的发布记录(审批中不可编辑/删除)。 */
    private boolean hasPendingPublish(Long skillId) {
        return publishMapper.hasPendingBySkillId(skillId);
    }

    // ==================== 变更草稿 ====================

    /**
     * 查看指定 Skill 的当前 PENDING 草稿,无草稿返回 null。
     */
    public SkillDraft getCurrentDraft(Long skillId) {
        return draftMapper.selectPendingBySkillId(skillId);
    }

    /**
     * 审批通过:校验审批人(或签)-> 存旧版本到版本历史 -> 应用草稿到主表 -> 标记草稿 APPROVED。
     */
    @Transactional
    public void approveDraft(Long draftId, String approverId, String comment) {
        SkillDraft draft = draftMapper.selectById(draftId);
        if (draft == null) {
            throw new DraftNotFoundException("DraftNotFound: " + draftId);
        }
        if (!"PENDING".equals(draft.getStatus())) {
            throw new DraftAlreadyPendingException("DraftAlreadyPending: " + draftId);
        }
        validateDraftApprover(draft.getSkillId(), approverId);

        Skill old = skillManageMapper.selectById(draft.getSkillId());
        saveVersion(old, approverId, "变更审批通过");

        Skill updated = Skill.builder()
                .id(old.getId())
                .name(draft.getName())
                .description(draft.getDescription())
                .content(draft.getContent())
                .category(draft.getCategory())
                .tags(draft.getTags())
                .ownerUserId(old.getOwnerUserId())
                .status("ACTIVE")
                .likeCount(old.getLikeCount())
                .createdAt(old.getCreatedAt())
                .updatedAt(old.getUpdatedAt())
                .deletedAt(old.getDeletedAt())
                .build();
        skillManageMapper.update(updated);

        draftMapper.updateStatus(draftId, "APPROVED", approverId, comment);

        approvalMapper.insert(SkillApproval.builder()
                .publishId(null)
                .draftId(draftId)
                .action("APPROVE")
                .operator(approverId)
                .comment(comment)
                .versionSnapshot(0)
                .createdAt(LocalDateTime.now())
                .build());

        recordOperation(draft.getSkillId(), null, approverId, "DRAFT_APPROVE", null, null);
    }

    /**
     * 审批退回:校验审批人(或签)-> 标记草稿 REJECTED。
     */
    @Transactional
    public void rejectDraft(Long draftId, String approverId, String comment) {
        SkillDraft draft = draftMapper.selectById(draftId);
        if (draft == null) {
            throw new DraftNotFoundException("DraftNotFound: " + draftId);
        }
        if (!"PENDING".equals(draft.getStatus())) {
            throw new DraftAlreadyPendingException("DraftAlreadyPending: " + draftId);
        }
        validateDraftApprover(draft.getSkillId(), approverId);

        draftMapper.updateStatus(draftId, "REJECTED", approverId, comment);

        approvalMapper.insert(SkillApproval.builder()
                .publishId(null)
                .draftId(draftId)
                .action("REJECT")
                .operator(approverId)
                .comment(comment)
                .versionSnapshot(0)
                .createdAt(LocalDateTime.now())
                .build());

        recordOperation(draft.getSkillId(), null, approverId, "DRAFT_REJECT", null, null);
    }

    /**
     * 待我审批的草稿列表。
     *
     * <p>{@code SkillDraftMapper.selectPendingByApprover} 的 XML 实现未使用
     * {@code approverUserId} 参数,返回的是所有"有已 APPROVED 发布"的 PENDING 草稿。
     * 因此这里先取候选集,再按或签审批人(已批准发布的 {@code currentApproverUserId})过滤。
     */
    public List<SkillDraft> pendingDraftsForApprover(String userId) {
        List<SkillDraft> candidates = draftMapper.selectPendingByApprover(userId);
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        List<SkillDraft> result = new ArrayList<>(candidates.size());
        for (SkillDraft draft : candidates) {
            List<SkillPublish> approved = publishMapper.selectApprovedBySkillId(draft.getSkillId());
            if (approved == null || approved.isEmpty()) {
                continue;
            }
            boolean isApprover = approved.stream()
                    .map(SkillPublish::getCurrentApproverUserId)
                    .anyMatch(userId::equals);
            if (isApprover) {
                result.add(draft);
            }
        }
        return result;
    }

    // ==================== 私有工具方法 ====================

    private void assertActive(Long skillId) {
        Skill s = get(skillId);
        if (!"ACTIVE".equals(s.getStatus())) {
            throw new IllegalStateException("SkillNotActive: " + skillId);
        }
    }

    private long currentLikeCount(Long skillId) {
        Long c = skillManageMapper.selectLikeCount(skillId);
        return c == null ? 0L : c;
    }

    private static Set<Long> nullToEmpty(Set<Long> set) {
        return set == null ? Set.of() : set;
    }

    /**
     * 记录操作历史。CREATE/UPDATE/DELETE/PUBLISH/APPROVE/REJECT/DISABLE/ENABLE/REFERENCE。
     * LIKE/UNLIKE 不记录(高频,审计价值低)。
     */
    private void recordOperation(Long skillId, Long publishId, String operator,
                                 String operation, String beforeData, String afterData) {
        operationHistoryMapper.insert(SkillOperationHistory.builder()
                .skillId(skillId)
                .publishId(publishId)
                .operator(operator)
                .operation(operation)
                .beforeData(beforeData)
                .afterData(afterData)
                .createdAt(LocalDateTime.now())
                .build());
    }

    /**
     * 或签审批人校验:该 Skill 已 APPROVED 发布关系中任一记录的
     * {@code currentApproverUserId} 等于当前用户即通过。
     */
    private void validateDraftApprover(Long skillId, String approverId) {
        List<SkillPublish> approved = publishMapper.selectApprovedBySkillId(skillId);
        if (approved == null || approved.isEmpty()) {
            throw new IllegalStateException("NoApprovedPublish: " + skillId);
        }
        boolean isApprover = approved.stream()
                .map(SkillPublish::getCurrentApproverUserId)
                .anyMatch(approverId::equals);
        if (!isApprover) {
            throw new NotApproverException("NotApprover: " + approverId);
        }
    }
}
