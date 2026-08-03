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
import com.agentscopea2a.v2.skillManager.dto.SkillFileReferenceItem;
import com.agentscopea2a.v2.skillManager.dto.SkillListItem;
import com.agentscopea2a.v2.skillManager.dto.SkillListQuery;
import com.agentscopea2a.v2.skillManager.entity.Skill;
import com.agentscopea2a.v2.skillManager.entity.SkillApproval;
import com.agentscopea2a.v2.skillManager.entity.SkillDraft;
import com.agentscopea2a.v2.skillManager.entity.SkillFileReference;
import com.agentscopea2a.v2.skillManager.entity.SkillLike;
import com.agentscopea2a.v2.skillManager.entity.SkillOperationHistory;
import com.agentscopea2a.v2.skillManager.entity.SkillPublish;
import com.agentscopea2a.v2.skillManager.entity.SkillReference;
import com.agentscopea2a.v2.skillManager.entity.SkillUserDisable;
import com.agentscopea2a.v2.skillManager.entity.SkillVersionHistory;
import com.agentscopea2a.v2.skillManager.mapper.SkillMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

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

    private final SkillMapper skillMapper;
    private final MockOrgService mockOrgService;
    /** 页面 Skill 双写桥接，用 ObjectProvider 避免启动顺序问题 */
    private final ObjectProvider<SkillManageBridge> bridgeProvider;

    /** 检索 body 缓存:retrieval_name -> content,60s TTL(与 SkillVectorIndex 缓存节奏一致)。 */
    private static final long BODY_CACHE_TTL_NANOS = TimeUnit.SECONDS.toNanos(60);
    private final ConcurrentHashMap<String, BodyCacheEntry> bodyCache = new ConcurrentHashMap<>();

    /** skill body 缓存条目:content + 过期时间戳(纳秒)。 */
    private record BodyCacheEntry(String content, long expireAtNanos) {}

    public SkillManageService(SkillMapper skillMapper,
                              MockOrgService mockOrgService,
                              ObjectProvider<SkillManageBridge> bridgeProvider) {
        this.skillMapper = skillMapper;
        this.mockOrgService = mockOrgService;
        this.bridgeProvider = bridgeProvider;
    }

    // ==================== Skill CRUD + 列表 ====================

    /**
     * 列表查询:支持视图/排序/筛选/分页,批量计算 liked/used/disabled 标记。
     * dimension 过滤已移至前端展示层,避免破坏 SQL 分页(LIMIT/OFFSET)。
     */
    public List<SkillListItem> list(SkillListQuery q) {
        List<Skill> skills = skillMapper.selectList(q);
        if (skills.isEmpty()) {
            return List.of();
        }
        List<Long> ids = skills.stream().map(Skill::getId).toList();
        String userId = q.getUserId();
        Set<Long> likedIds = nullToEmpty(skillMapper.selectLikedSkillIds(userId, ids));
        Set<Long> usedIds = nullToEmpty(skillMapper.selectUsedSkillIds(userId, ids));
        Set<Long> disabledIds = nullToEmpty(skillMapper.selectDisabledSkillIds(userId, ids));
        List<SkillPublish> approved = skillMapper.selectApprovedBySkillIds(ids);
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
            // 所有者对自己创建的 Skill 视为"已使用"(初次即 🟢 已使用,无需先点引用)
            boolean used = usedIds.contains(s.getId()) || (userId != null && userId.equals(s.getOwnerUserId()));
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
        return skillMapper.selectAllTags();
    }

    @Transactional("gaussTransactionManager")
    public Skill create(Skill skill, String ownerUserId) {
        if (skillMapper.existsByName(skill.getName())) {
            throw new IllegalStateException("SkillNameConflict: " + skill.getName());
        }
        skill.setOwnerUserId(ownerUserId);
        skill.setStatus("ACTIVE");
        skill.setLikeCount(0L);
        skill.setCreatedAt(LocalDateTime.now());
        skill.setUpdatedAt(LocalDateTime.now());
        skillMapper.insertSkill(skill);
        Skill saved = skillMapper.selectById(skill.getId());

        // 创建者默认引用自己的 Skill,使其出现在"我使用的"列表
        // (幂等;自引用 owner==creator,reference 内部跳过检索空间复制)
        reference(saved.getId(), ownerUserId);

        // 双写桥接：同步到检索索引（skill_index + SKILL.md + embedding）
        SkillManageBridge bridge = bridgeProvider.getIfAvailable();
        if (bridge != null) {
            String retrievalName = bridge.syncToRetrievalIndex(saved);
            saved.setRetrievalName(retrievalName);
            skillMapper.updateSkill(saved);
        }
        return saved;
    }

    /**
     * PR5 - Agent 调用 save_skill 创建的 skill 写入 skill_manage 表。
     * 与 {@link #create} 不同:
     * - 跳过 existsByName 冲突检查(retrievalName 已含 userId 前缀)
     * - 不调用 SkillManageBridge.syncToRetrievalIndex(SkillSaveTool 已写 skill_index + 文件)
     * - 直接 insert skill_manage 表,retrieval_name = retrievalName
     *
     * @param skill         Skill 实体(name/description/content/status 已填)
     * @param ownerUserId   所有者 userId
     * @param retrievalName 检索名(如 usr_user_001_quality_query)
     */
    @Transactional("gaussTransactionManager")
    public void createForAgent(Skill skill, String ownerUserId, String retrievalName) {
        skill.setOwnerUserId(ownerUserId);
        skill.setStatus("ACTIVE");
        skill.setLikeCount(0L);
        skill.setRetrievalName(retrievalName);
        skill.setCreatedAt(LocalDateTime.now());
        skill.setUpdatedAt(LocalDateTime.now());
        skillMapper.insertSkill(skill);

        // 创建者默认引用自己的 Skill,使其出现在"我使用的"列表
        // (幂等;自引用 owner==creator,reference 内部跳过检索空间复制)
        reference(skill.getId(), ownerUserId);
    }

    public Skill get(Long id) {
        Skill s = skillMapper.selectById(id);
        if (s == null || "DELETED".equals(s.getStatus())) {
            throw new IllegalStateException("SkillNotFound: " + id);
        }
        return s;
    }

    @Transactional("gaussTransactionManager")
    public Skill update(Long id, Skill patch, String userId) {
        Skill s = get(id);
        if (!s.getOwnerUserId().equals(userId)) {
            throw new IllegalStateException("SkillAccessDenied: " + id);
        }
        if (hasPendingPublish(id)) {
            throw new IllegalStateException("SkillPendingApproval: " + id);
        }
        if (patch.getName() != null && !patch.getName().equals(s.getName())
                && skillMapper.existsByName(patch.getName())) {
            throw new IllegalStateException("SkillNameConflict: " + patch.getName());
        }
        if (patch.getName() != null) s.setName(patch.getName());
        if (patch.getDescription() != null) s.setDescription(patch.getDescription());
        if (patch.getContent() != null) s.setContent(patch.getContent());
        if (patch.getCategory() != null) s.setCategory(patch.getCategory());
        if (patch.getTags() != null) s.setTags(patch.getTags());
        s.setUpdatedAt(LocalDateTime.now());
        skillMapper.updateSkill(s);
        invalidateBodyCache(s.getRetrievalName()); // 失效检索 body 缓存
        Skill updated = skillMapper.selectById(id);

        // 双写桥接：覆盖 SKILL.md + 更新 skill_index
        SkillManageBridge bridge = bridgeProvider.getIfAvailable();
        if (bridge != null) {
            bridge.syncToRetrievalIndex(updated);
        }
        return updated;
    }

    @Transactional("gaussTransactionManager")
    public void delete(Long id, String userId) {
        Skill s = get(id);
        if (!s.getOwnerUserId().equals(userId)) {
            throw new IllegalStateException("SkillAccessDenied: " + id);
        }
        if (hasPendingPublish(id)) {
            throw new IllegalStateException("SkillPendingApproval: " + id);
        }
        skillMapper.softDelete(id);
        invalidateBodyCache(s.getRetrievalName()); // 失效检索 body 缓存

        // 双写桥接：从检索索引移除
        SkillManageBridge bridge = bridgeProvider.getIfAvailable();
        if (bridge != null) {
            bridge.removeFromRetrievalIndex(s.getRetrievalName());
        }
    }

    // ==================== 版本历史 ====================

    /** 指定 Skill 的版本历史。 */
    public List<SkillVersionHistory> selectVersionsBySkillId(Long skillId) {
        return skillMapper.selectVersionBySkillId(skillId);
    }

    /**
     * 存旧版本快照到版本历史(供草稿审批通过时调用)。
     */
    private void saveVersion(Skill skill, String editedBy, String editReason) {
        Integer maxVersion = skillMapper.selectMaxVersion(skill.getId());
        int nextVersion = maxVersion == null ? 1 : maxVersion + 1;
        skillMapper.insertSkillVersionHistory(SkillVersionHistory.builder()
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

    @Transactional("gaussTransactionManager")
    public LikeStatus like(Long skillId, String userId) {
        assertActive(skillId);
        if (skillMapper.selectLikeByUserSkill(userId, skillId) != null) {
            return new LikeStatus(true, currentLikeCount(skillId));
        }
        try {
            skillMapper.insertSkillLike(SkillLike.builder()
                    .skillId(skillId).userId(userId).createdAt(LocalDateTime.now()).build());
            skillMapper.incrementLikeCount(skillId);
        } catch (DuplicateKeyException e) {
            log.debug("concurrent like race, treat as idempotent: skill={} user={}", skillId, userId);
        }
        return new LikeStatus(true, currentLikeCount(skillId));
    }

    @Transactional("gaussTransactionManager")
    public LikeStatus unlike(Long skillId, String userId) {
        assertActive(skillId);
        if (skillMapper.selectLikeByUserSkill(userId, skillId) == null) {
            return new LikeStatus(false, currentLikeCount(skillId));
        }
        skillMapper.deleteLikeByUserSkill(userId, skillId);
        skillMapper.decrementLikeCount(skillId);
        return new LikeStatus(false, currentLikeCount(skillId));
    }

    public LikeStatus getLikeStatus(Long skillId, String userId) {
        boolean liked = skillMapper.selectLikeByUserSkill(userId, skillId) != null;
        return new LikeStatus(liked, currentLikeCount(skillId));
    }

    // ==================== 引用(幂等) ====================

    @Transactional("gaussTransactionManager")
    public void reference(Long skillId, String userId) {
        Skill skill = get(skillId); // 校验 Skill 存在
        if (skillMapper.existsReferenceByCreatorTarget(userId, skillId)) {
            return; // 幂等
        }
        try {
            skillMapper.insertSkillReference(SkillReference.builder()
                    .sourceSkillId(skillId).targetSkillId(skillId).creator(userId)
                    .createdAt(LocalDateTime.now()).build());
        } catch (DuplicateKeyException e) {
            log.debug("concurrent reference race, idempotent: skill={} user={}", skillId, userId);
        }
        // 引用不复制文件:检索层通过 skill_reference 表感知引用关系
    }

    @Transactional("gaussTransactionManager")
    public void unreference(Long skillId, String userId) {
        get(skillId); // 校验 Skill 存在
        skillMapper.deleteReferenceByCreatorTarget(userId, skillId);
        // 引用不复制文件,取消引用也无需清理文件
    }

    /**
     * 查询用户可见的全部 user skill 的 retrieval_name 列表,供检索层一次性过滤使用。
     *
     * <p>可见集合 = 用户在 {@code skill_reference} 表中引用过的 skill(含自己创建的自引用 +
     * 引用别人的),只返回有 retrieval_name 且非 DELETED 的 skill。
     *
     * <p>{@code owner_user_id} 不再参与检索层过滤,仅用于删除权限校验。
     */
    public List<String> getVisibleRetrievalNames(String userId) {
        List<Long> refSkillIds = skillMapper.selectReferencedSkillIdsByCreator(userId);
        if (refSkillIds == null || refSkillIds.isEmpty()) {
            return List.of();
        }
        List<Skill> skills = skillMapper.selectByIds(refSkillIds);
        if (skills == null || skills.isEmpty()) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        for (Skill s : skills) {
            if ("DELETED".equals(s.getStatus())) continue;
            String rn = s.getRetrievalName();
            if (rn != null && !rn.isBlank()) {
                names.add(rn);
            }
        }
        return names;
    }

    /**
     * 按 retrieval_name 取 skill body(content),供检索 Hook 读 user skill 用。
     * 60s TTL 缓存,miss 查 DB;skill 不存在/已删除返回 null(不缓存 null,新建后立即可见)。
     */
    public String getSkillContentByRetrievalName(String retrievalName) {
        if (retrievalName == null || retrievalName.isBlank()) return null;
        long now = System.nanoTime();
        BodyCacheEntry cached = bodyCache.get(retrievalName);
        if (cached != null && cached.expireAtNanos() > now) {
            return cached.content();
        }
        String content = skillMapper.selectContentByRetrievalName(retrievalName);
        if (content != null) {
            bodyCache.put(retrievalName, new BodyCacheEntry(content, now + BODY_CACHE_TTL_NANOS));
        }
        return content;
    }

    /** 失效 body 缓存(skill 内容变更/删除后调用)。 */
    public void invalidateBodyCache(String retrievalName) {
        if (retrievalName != null) bodyCache.remove(retrievalName);
    }

    public List<Long> listMyReferences(String userId) {
        return skillMapper.selectReferencedSkillIdsByCreator(userId);
    }

    public List<String> listReferencers(Long skillId) {
        return skillMapper.selectReferencersBySkillId(skillId);
    }

    // ==================== 用户禁用(幂等) ====================

    @Transactional("gaussTransactionManager")
    public void disable(Long skillId, String userId) {
        get(skillId); // 校验 Skill 存在
        if (skillMapper.existsDisableByUserSkill(userId, skillId)) {
            return; // 幂等
        }
        try {
            skillMapper.insertSkillUserDisable(SkillUserDisable.builder()
                    .skillId(skillId).userId(userId)
                    .createdAt(LocalDateTime.now()).build());
            recordOperation(skillId, null, userId, "DISABLE", null, null);
        } catch (DuplicateKeyException e) {
            log.debug("concurrent disable race, idempotent: skill={} user={}", skillId, userId);
        }
    }

    @Transactional("gaussTransactionManager")
    public void enable(Long skillId, String userId) {
        get(skillId); // 校验 Skill 存在
        skillMapper.deleteDisableByUserSkill(userId, skillId);
        recordOperation(skillId, null, userId, "ENABLE", null, null);
    }

    public boolean isDisabled(Long skillId, String userId) {
        return skillMapper.existsDisableByUserSkill(userId, skillId);
    }

    // ==================== 发布审批 ====================

    /**
     * 提交发布申请。仅 Skill 所有者可提交。
     *
     * @return 新建的发布记录 id
     */
    @Transactional("gaussTransactionManager")
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
        skillMapper.insertSkillPublish(publish);
        Long publishId = publish.getId();

        skillMapper.insertSkillApproval(SkillApproval.builder()
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
    @Transactional("gaussTransactionManager")
    public void approvePublish(Long publishId, String approverId, String comment) {
        SkillPublish p = skillMapper.selectPublishById(publishId);
        if (p == null) {
            throw new IllegalStateException("PublishNotFound: " + publishId);
        }
        if (!"PENDING".equals(p.getStatus())) {
            throw new PublishAlreadyApprovedException("PublishAlreadyApproved: " + publishId);
        }
        if (!approverId.equals(p.getCurrentApproverUserId())) {
            throw new NotApproverException("NotApprover: " + approverId);
        }
        skillMapper.updatePublishStatus(publishId, "APPROVED", approverId, comment);
        skillMapper.insertSkillApproval(SkillApproval.builder()
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
    @Transactional("gaussTransactionManager")
    public void rejectPublish(Long publishId, String approverId, String comment) {
        SkillPublish p = skillMapper.selectPublishById(publishId);
        if (p == null) {
            throw new IllegalStateException("PublishNotFound: " + publishId);
        }
        if (!"PENDING".equals(p.getStatus())) {
            throw new PublishAlreadyApprovedException("PublishAlreadyApproved: " + publishId);
        }
        if (!approverId.equals(p.getCurrentApproverUserId())) {
            throw new NotApproverException("NotApprover: " + approverId);
        }
        skillMapper.updatePublishStatus(publishId, "REJECTED", approverId, comment);
        skillMapper.insertSkillApproval(SkillApproval.builder()
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
        return skillMapper.selectPendingPublishByApprover(userId);
    }

    /** 返回指定审批人已处理过的发布列表(APPROVED/REJECTED)。 */
    public List<SkillPublish> historyPublishesForApprover(String userId) {
        return skillMapper.selectHistoryByApprover(userId);
    }

    /** 返回指定发布记录的审批历史(按时间正序)。 */
    public List<SkillApproval> publishApprovalHistory(Long publishId) {
        return skillMapper.selectApprovalByPublishId(publishId);
    }

    /** 返回指定 Skill 的全部发布记录(含 APPROVED 和 PENDING),按创建时间倒序。 */
    public List<SkillPublish> listPublishesBySkillId(Long skillId) {
        return skillMapper.selectPublishBySkillId(skillId);
    }

    /** 判断指定 Skill 是否存在审批中的发布记录(审批中不可编辑/删除)。 */
    private boolean hasPendingPublish(Long skillId) {
        return skillMapper.hasPendingBySkillId(skillId);
    }

    // ==================== 变更草稿 ====================

    /**
     * 查看指定 Skill 的当前 PENDING 草稿,无草稿返回 null。
     */
    public SkillDraft getCurrentDraft(Long skillId) {
        return skillMapper.selectPendingDraftBySkillId(skillId);
    }

    /**
     * 审批通过:校验审批人(或签)-> 存旧版本到版本历史 -> 应用草稿到主表 -> 标记草稿 APPROVED。
     */
    @Transactional("gaussTransactionManager")
    public void approveDraft(Long draftId, String approverId, String comment) {
        SkillDraft draft = skillMapper.selectDraftById(draftId);
        if (draft == null) {
            throw new DraftNotFoundException("DraftNotFound: " + draftId);
        }
        if (!"PENDING".equals(draft.getStatus())) {
            throw new DraftAlreadyPendingException("DraftAlreadyPending: " + draftId);
        }
        validateDraftApprover(draft.getSkillId(), approverId);

        Skill old = skillMapper.selectById(draft.getSkillId());
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
                .retrievalName(old.getRetrievalName())
                .createdAt(old.getCreatedAt())
                .updatedAt(old.getUpdatedAt())
                .deletedAt(old.getDeletedAt())
                .build();
        skillMapper.updateSkill(updated);

        skillMapper.updateDraftStatus(draftId, "APPROVED", approverId, comment);

        skillMapper.insertSkillApproval(SkillApproval.builder()
                .publishId(null)
                .draftId(draftId)
                .action("APPROVE")
                .operator(approverId)
                .comment(comment)
                .versionSnapshot(0)
                .createdAt(LocalDateTime.now())
                .build());

        recordOperation(draft.getSkillId(), null, approverId, "DRAFT_APPROVE", null, null);

        // 双写桥接：草稿审批通过后同步新内容到检索索引
        SkillManageBridge bridge = bridgeProvider.getIfAvailable();
        if (bridge != null) {
            bridge.syncToRetrievalIndex(updated);
        }
    }

    /**
     * 审批退回:校验审批人(或签)-> 标记草稿 REJECTED。
     */
    @Transactional("gaussTransactionManager")
    public void rejectDraft(Long draftId, String approverId, String comment) {
        SkillDraft draft = skillMapper.selectDraftById(draftId);
        if (draft == null) {
            throw new DraftNotFoundException("DraftNotFound: " + draftId);
        }
        if (!"PENDING".equals(draft.getStatus())) {
            throw new DraftAlreadyPendingException("DraftAlreadyPending: " + draftId);
        }
        validateDraftApprover(draft.getSkillId(), approverId);

        skillMapper.updateDraftStatus(draftId, "REJECTED", approverId, comment);

        skillMapper.insertSkillApproval(SkillApproval.builder()
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
     * <p>{@code selectPendingDraftByApprover} 的 XML 实现未使用
     * {@code approverUserId} 参数,返回的是所有"有已 APPROVED 发布"的 PENDING 草稿。
     * 因此这里先取候选集,再按或签审批人(已批准发布的 {@code currentApproverUserId})过滤。
     */
    public List<SkillDraft> pendingDraftsForApprover(String userId) {
        List<SkillDraft> candidates = skillMapper.selectPendingDraftByApprover(userId);
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        List<SkillDraft> result = new ArrayList<>(candidates.size());
        for (SkillDraft draft : candidates) {
            List<SkillPublish> approved = skillMapper.selectApprovedBySkillId(draft.getSkillId());
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
        Long c = skillMapper.selectLikeCount(skillId);
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
        skillMapper.insertSkillOperationHistory(SkillOperationHistory.builder()
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
        List<SkillPublish> approved = skillMapper.selectApprovedBySkillId(skillId);
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

    // ==================== Skill 文件附件引用 ====================

    /**
     * 获取 Skill 引用的文件列表。
     */
    public List<SkillFileReferenceItem> listSkillFiles(Long skillId) {
        get(skillId); // 校验 Skill 存在
        return skillMapper.selectSkillFileReferences(skillId);
    }

    /**
     * Skill 引用一个文件(幂等)。
     */
    @Transactional("gaussTransactionManager")
    public void addFileReference(Long skillId, Long fileId, String referenceType) {
        get(skillId); // 校验 Skill 存在
        if (skillMapper.selectFileById(fileId) == null) {
            throw new IllegalStateException("FileNotFound: " + fileId);
        }
        if (skillMapper.existsSkillFileReference(skillId, fileId)) {
            return; // 幂等
        }
        try {
            skillMapper.insertSkillFileReference(SkillFileReference.builder()
                    .skillId(skillId)
                    .fileId(fileId)
                    .referenceType(referenceType == null ? "ATTACHMENT" : referenceType)
                    .createdAt(LocalDateTime.now())
                    .build());
        } catch (DuplicateKeyException e) {
            log.debug("concurrent file reference race, idempotent: skill={} file={}", skillId, fileId);
        }
    }

    /**
     * Skill 取消引用一个文件。
     */
    @Transactional("gaussTransactionManager")
    public void removeFileReference(Long skillId, Long fileId) {
        get(skillId); // 校验 Skill 存在
        skillMapper.deleteSkillFileReference(skillId, fileId);
    }
}
