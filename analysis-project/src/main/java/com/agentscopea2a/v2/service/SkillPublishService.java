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
import com.agentscopea2a.entity.SkillApproval;
import com.agentscopea2a.entity.SkillPublish;
import com.agentscopea2a.mapper.mysql.SkillApprovalMapper;
import com.agentscopea2a.mapper.mysql.SkillPublishMapper;
import com.agentscopea2a.v2.exception.NotApproverException;
import com.agentscopea2a.v2.exception.PublishAlreadyApprovedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 发布审批 Service:负责 Skill 的发布申请、审批通过/退回与待办/历史查询(§12.3.2)。
 *
 * <p>发布流程:Skill 所有者提交发布申请 -> 确定组织审批人 -> 审批人通过或退回。
 * 全程留痕:每次状态流转写 {@link SkillApproval} 与操作历史。
 */
@Service
public class SkillPublishService {

    private static final Logger log = LoggerFactory.getLogger(SkillPublishService.class);

    private final SkillPublishMapper mapper;
    private final SkillApprovalMapper approvalMapper;
    private final SkillService skillService;
    private final SkillOperationHistoryService historyService;
    private final MockOrgService mockOrgService;

    public SkillPublishService(SkillPublishMapper mapper, SkillApprovalMapper approvalMapper,
                               SkillService skillService, SkillOperationHistoryService historyService,
                               MockOrgService mockOrgService) {
        this.mapper = mapper;
        this.approvalMapper = approvalMapper;
        this.skillService = skillService;
        this.historyService = historyService;
        this.mockOrgService = mockOrgService;
    }

    /**
     * 提交发布申请。仅 Skill 所有者可提交。
     *
     * @return 新建的发布记录 id
     */
    @Transactional
    public Long submitPublish(Long skillId, String targetType, String targetId, String targetName, String userId) {
        Skill s = skillService.get(skillId); // 不存在抛 IllegalStateException
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
        mapper.insert(publish);
        Long publishId = publish.getId();

        approvalMapper.insert(SkillApproval.builder()
                .publishId(publishId)
                .draftId(null)
                .action("SUBMIT")
                .operator(userId)
                .comment(null)
                .versionSnapshot(0)
                .build());
        historyService.record(skillId, publishId, userId, "PUBLISH_SUBMIT", null, null);
        log.info("publish submitted: skill={} publish={} submitter={} approver={}",
                skillId, publishId, userId, approverId);
        return publishId;
    }

    /**
     * 审批通过。仅当前审批人可操作,且发布记录须处于 PENDING 状态。
     */
    @Transactional
    public void approve(Long publishId, String approverId, String comment) {
        SkillPublish p = mapper.selectById(publishId);
        if (p == null) {
            throw new IllegalStateException("PublishNotFound: " + publishId);
        }
        if (!"PENDING".equals(p.getStatus())) {
            throw new PublishAlreadyApprovedException("PublishAlreadyApproved: " + publishId);
        }
        if (!approverId.equals(p.getCurrentApproverUserId())) {
            throw new NotApproverException("NotApprover: " + approverId);
        }
        mapper.updateStatus(publishId, "APPROVED", approverId, comment);
        approvalMapper.insert(SkillApproval.builder()
                .publishId(publishId)
                .draftId(null)
                .action("APPROVE")
                .operator(approverId)
                .comment(comment)
                .versionSnapshot(0)
                .build());
        historyService.record(p.getSkillId(), publishId, approverId, "PUBLISH_APPROVE", null, null);
        log.info("publish approved: publish={} approver={}", publishId, approverId);
    }

    /**
     * 审批退回。仅当前审批人可操作,且发布记录须处于 PENDING 状态。
     */
    @Transactional
    public void reject(Long publishId, String approverId, String comment) {
        SkillPublish p = mapper.selectById(publishId);
        if (p == null) {
            throw new IllegalStateException("PublishNotFound: " + publishId);
        }
        if (!"PENDING".equals(p.getStatus())) {
            throw new PublishAlreadyApprovedException("PublishAlreadyApproved: " + publishId);
        }
        if (!approverId.equals(p.getCurrentApproverUserId())) {
            throw new NotApproverException("NotApprover: " + approverId);
        }
        mapper.updateStatus(publishId, "REJECTED", approverId, comment);
        approvalMapper.insert(SkillApproval.builder()
                .publishId(publishId)
                .draftId(null)
                .action("REJECT")
                .operator(approverId)
                .comment(comment)
                .versionSnapshot(0)
                .build());
        historyService.record(p.getSkillId(), publishId, approverId, "PUBLISH_REJECT", null, null);
        log.info("publish rejected: publish={} approver={}", publishId, approverId);
    }

    /**
     * 返回指定审批人名下的待审发布列表。
     */
    public List<SkillPublish> pendingForApprover(String userId) {
        return mapper.selectPendingByApprover(userId);
    }

    /**
     * 返回指定发布记录的审批历史(按时间正序)。
     */
    public List<SkillApproval> approvalHistory(Long publishId) {
        return approvalMapper.selectByPublishId(publishId);
    }

    /**
     * 返回指定 Skill 的全部发布记录(含 APPROVED 和 PENDING),按创建时间倒序。
     */
    public List<SkillPublish> listBySkillId(Long skillId) {
        return mapper.selectBySkillId(skillId);
    }

    /**
     * 判断指定 Skill 是否已有任意一次发布审批通过记录(供 SkillDraftService 使用)。
     */
    public boolean hasApproved(Long skillId) {
        return mapper.hasApprovedBySkillId(skillId);
    }

    /**
     * 批量查询已审批通过的发布记录(供 SkillService 可用性计算使用)。
     * 传入空列表时直接返回空列表,避免生成非法 SQL。
     */
    public List<SkillPublish> approvedForSkillIds(List<Long> skillIds) {
        if (skillIds == null || skillIds.isEmpty()) {
            return List.of();
        }
        return mapper.selectApprovedBySkillIds(skillIds);
    }
}
