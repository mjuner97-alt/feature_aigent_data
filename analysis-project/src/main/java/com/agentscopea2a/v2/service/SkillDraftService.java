/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/LICENSE/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.agentscopea2a.v2.service;

import com.agentscopea2a.entity.Skill;
import com.agentscopea2a.entity.SkillApproval;
import com.agentscopea2a.entity.SkillDraft;
import com.agentscopea2a.entity.SkillPublish;
import com.agentscopea2a.mapper.mysql.SkillApprovalMapper;
import com.agentscopea2a.mapper.mysql.SkillDraftMapper;
import com.agentscopea2a.mapper.mysql.SkillManageMapper;
import com.agentscopea2a.mapper.mysql.SkillPublishMapper;
import com.agentscopea2a.v2.exception.DraftAlreadyPendingException;
import com.agentscopea2a.v2.exception.DraftNotFoundException;
import com.agentscopea2a.v2.exception.NotApproverException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 变更草稿 Service(§12.3.2 / §12.3.4)。
 *
 * <p>已发布 Skill 的内容变更需走草稿审批:编辑时把变更暂存到 {@code skill_draft},
 * 审批通过后再应用到 {@code skill_manage} 主表。审批人为或签模式,取该 Skill 已 APPROVED
 * 发布关系中记录的 {@code currentApproverUserId}。
 *
 * <p>userId 经请求头 {@code X-User-Id} 传入(无 Spring Security)。
 */
@Service
public class SkillDraftService {

    private final SkillDraftMapper draftMapper;
    private final SkillManageMapper skillManageMapper;
    private final SkillApprovalMapper approvalMapper;
    private final SkillPublishMapper publishMapper;
    private final SkillService skillService;
    private final SkillVersionHistoryService versionHistoryService;
    private final SkillOperationHistoryService operationHistoryService;

    public SkillDraftService(SkillDraftMapper draftMapper,
                             SkillManageMapper skillManageMapper,
                             SkillApprovalMapper approvalMapper,
                             SkillPublishMapper publishMapper,
                             SkillService skillService,
                             SkillVersionHistoryService versionHistoryService,
                             SkillOperationHistoryService operationHistoryService) {
        this.draftMapper = draftMapper;
        this.skillManageMapper = skillManageMapper;
        this.approvalMapper = approvalMapper;
        this.publishMapper = publishMapper;
        this.skillService = skillService;
        this.versionHistoryService = versionHistoryService;
        this.operationHistoryService = operationHistoryService;
    }

    /**
     * 提交变更草稿:仅 Skill 所有者可提交,同一 Skill 同时只允许一个 PENDING 草稿。
     *
     * @return 新建草稿 id
     */
    @Transactional
    public Long submitDraft(Long skillId, Skill patch, String userId) {
        Skill s = skillService.get(skillId);
        if (!s.getOwnerUserId().equals(userId)) {
            throw new IllegalStateException("SkillAccessDenied: " + skillId);
        }
        if (draftMapper.selectPendingBySkillId(skillId) != null) {
            throw new DraftAlreadyPendingException("DraftAlreadyPending: " + skillId);
        }

        SkillDraft draft = SkillDraft.builder()
                .skillId(skillId)
                .name(patch.getName() != null ? patch.getName() : s.getName())
                .description(patch.getDescription() != null ? patch.getDescription() : s.getDescription())
                .content(patch.getContent() != null ? patch.getContent() : s.getContent())
                .category(patch.getCategory() != null ? patch.getCategory() : s.getCategory())
                .tags(patch.getTags() != null ? patch.getTags() : s.getTags())
                .status("PENDING")
                .submitter(userId)
                .submittedAt(LocalDateTime.now())
                .build();
        draftMapper.insert(draft);

        approvalMapper.insert(SkillApproval.builder()
                .publishId(null)
                .draftId(draft.getId())
                .action("SUBMIT")
                .operator(userId)
                .comment(null)
                .versionSnapshot(0)
                .createdAt(LocalDateTime.now())
                .build());

        operationHistoryService.record(skillId, null, userId, "DRAFT_SUBMIT", null, null);
        return draft.getId();
    }

    /**
     * 审批通过:校验审批人(或签)→ 存旧版本到版本历史 → 应用草稿到主表 → 标记草稿 APPROVED。
     */
    @Transactional
    public void approve(Long draftId, String approverId, String comment) {
        SkillDraft draft = draftMapper.selectById(draftId);
        if (draft == null) {
            throw new DraftNotFoundException("DraftNotFound: " + draftId);
        }
        if (!"PENDING".equals(draft.getStatus())) {
            throw new DraftAlreadyPendingException("DraftAlreadyPending: " + draftId);
        }
        validateApprover(draft.getSkillId(), approverId);

        Skill old = skillManageMapper.selectById(draft.getSkillId());
        versionHistoryService.saveVersion(old, approverId, "变更审批通过");

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

        operationHistoryService.record(draft.getSkillId(), null, approverId, "DRAFT_APPROVE", null, null);
    }

    /**
     * 审批退回:校验审批人(或签)→ 标记草稿 REJECTED。
     */
    @Transactional
    public void reject(Long draftId, String approverId, String comment) {
        SkillDraft draft = draftMapper.selectById(draftId);
        if (draft == null) {
            throw new DraftNotFoundException("DraftNotFound: " + draftId);
        }
        if (!"PENDING".equals(draft.getStatus())) {
            throw new DraftAlreadyPendingException("DraftAlreadyPending: " + draftId);
        }
        validateApprover(draft.getSkillId(), approverId);

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

        operationHistoryService.record(draft.getSkillId(), null, approverId, "DRAFT_REJECT", null, null);
    }

    /**
     * 查看指定 Skill 的当前 PENDING 草稿,无草稿返回 null。
     */
    public SkillDraft getCurrentDraft(Long skillId) {
        return draftMapper.selectPendingBySkillId(skillId);
    }

    /**
     * 待我审批的草稿列表。
     *
     * <p>{@code SkillDraftMapper.selectPendingByApprover} 的 XML 实现未使用
     * {@code approverUserId} 参数,返回的是所有"有已 APPROVED 发布"的 PENDING 草稿。
     * 因此这里先取候选集,再按或签审批人(已批准发布的 {@code currentApproverUserId})过滤。
     */
    public List<SkillDraft> pendingForApprover(String userId) {
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

    /**
     * 指定草稿的审批操作历史。
     */
    public List<SkillApproval> draftApprovalHistory(Long draftId) {
        return approvalMapper.selectByDraftId(draftId);
    }

    /**
     * 或签审批人校验:该 Skill 已 APPROVED 发布关系中任一记录的
     * {@code currentApproverUserId} 等于当前用户即通过。
     */
    private void validateApprover(Long skillId, String approverId) {
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
