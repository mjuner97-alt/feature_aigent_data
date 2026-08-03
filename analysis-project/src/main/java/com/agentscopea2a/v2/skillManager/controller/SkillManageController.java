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
package com.agentscopea2a.v2.skillManager.controller;

import com.agentscopea2a.v2.skillManager.dto.LikeStatus;
import com.agentscopea2a.v2.skillManager.dto.SkillListItem;
import com.agentscopea2a.v2.skillManager.dto.SkillListQuery;
import com.agentscopea2a.v2.skillManager.dto.SkillFileReferenceItem;
import com.agentscopea2a.v2.skillManager.dto.SkillFileReferenceRequest;
import com.agentscopea2a.v2.skillManager.entity.Skill;
import com.agentscopea2a.v2.skillManager.entity.SkillApproval;
import com.agentscopea2a.v2.skillManager.entity.SkillDraft;
import com.agentscopea2a.v2.skillManager.entity.SkillPublish;
import com.agentscopea2a.v2.skillManager.entity.SkillVersionHistory;
import com.agentscopea2a.v2.skillManager.service.MockOrgService;
import com.agentscopea2a.v2.skillManager.service.SkillManageService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Skill 管理 REST 接口(合并版)。userId 经 X-User-Id 请求头传入。
 *
 * <p>包含全部 Skill 相关操作:CRUD、列表查询、点赞、引用、用户禁用、发布审批、变更草稿。
 * 路径分三段:Skill 操作挂在 {@code /api/skills/...} 下,
 * 发布审批操作挂在 {@code /api/publish/...} 下,
 * 草稿审批操作挂在 {@code /api/draft/...} 下,故类级前缀仅取 {@code /api}。
 */
@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*", maxAge = 3600)
public class SkillManageController {

    private final SkillManageService skillService;
    private final MockOrgService mockOrgService;

    public SkillManageController(SkillManageService skillService, MockOrgService mockOrgService) {
        this.skillService = skillService;
        this.mockOrgService = mockOrgService;
    }

    // ==================== Skill CRUD + 列表 (§12.1) ====================

    /**
     * 列表(视图/排序/筛选/分页)。
     */
    @GetMapping("/skills")
    public List<SkillListItem> list(
            @RequestParam(name = "view", required = false) String view,
            @RequestParam(name = "sort", required = false) String sort,
            @RequestParam(name = "category", required = false) String category,
            @RequestParam(name = "tag", required = false) String tag,
            @RequestParam(name = "keyword", required = false) String keyword,
            @RequestParam(name = "limit", required = false) Integer limit,
            @RequestParam(name = "offset", required = false) Integer offset,
            @RequestParam(name = "dimension", required = false) String dimension,
            @RequestHeader("X-User-Id") String userId) {
        return skillService.list(new SkillListQuery(view, sort, category, tag, keyword, limit, offset, userId, dimension));
    }

    /**
     * 查询全部 ACTIVE Skill 的去重 tag 列表。
     */
    @GetMapping("/skills/tags")
    public List<String> tags() {
        return skillService.getAllTags();
    }

    /**
     * 创建 Skill。
     */
    @PostMapping("/skills")
    public Skill create(@RequestBody Skill skill, @RequestHeader("X-User-Id") String userId) {
        return skillService.create(skill, userId);
    }

    /**
     * 查询 Skill 详情。
     */
    @GetMapping("/skills/get")
    public Skill get(@RequestParam(name = "id") Long id) {
        return skillService.get(id);
    }

    /**
     * 更新 Skill(仅所有者)。
     */
    @PutMapping("/skills")
    public Skill update(@RequestParam(name = "id") Long id, @RequestBody Skill patch,
                        @RequestHeader("X-User-Id") String userId) {
        return skillService.update(id, patch, userId);
    }

    /**
     * 删除 Skill(软删除,仅所有者)。
     */
    @DeleteMapping("/skills")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@RequestParam(name = "id") Long id, @RequestHeader("X-User-Id") String userId) {
        skillService.delete(id, userId);
    }

    /**
     * 指定 Skill 的版本历史。
     */
    @GetMapping("/skills/{id}/versions")
    public List<SkillVersionHistory> versions(@PathVariable Long id) {
        return skillService.selectVersionsBySkillId(id);
    }

    // ==================== 点赞 (§12.2) ====================

    /**
     * 点赞(幂等 toggle)。
     */
    @PostMapping("/skills/{id}/like")
    public LikeStatus like(@PathVariable Long id, @RequestHeader("X-User-Id") String userId) {
        return skillService.like(id, userId);
    }

    /**
     * 取消点赞(幂等 toggle)。
     */
    @DeleteMapping("/skills/{id}/like")
    public LikeStatus unlike(@PathVariable Long id, @RequestHeader("X-User-Id") String userId) {
        return skillService.unlike(id, userId);
    }

    /**
     * 点赞状态。
     */
    @GetMapping("/skills/{id}/like")
    public LikeStatus likeStatus(@PathVariable Long id, @RequestHeader("X-User-Id") String userId) {
        return skillService.getLikeStatus(id, userId);
    }

    // ==================== 引用 (§12.3) ====================

    /**
     * 引用 Skill(幂等)。撑"我使用的 Skill"视图。
     */
    @PostMapping("/skills/{id}/reference")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reference(@PathVariable Long id, @RequestHeader("X-User-Id") String userId) {
        skillService.reference(id, userId);
    }

    /**
     * 取消引用(幂等)。
     */
    @DeleteMapping("/skills/{id}/reference")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unreference(@PathVariable Long id, @RequestHeader("X-User-Id") String userId) {
        skillService.unreference(id, userId);
    }

    /**
     * 我引用的 Skill id 列表。
     */
    @GetMapping("/skills/my-references")
    public List<Long> myReferences(@RequestHeader("X-User-Id") String userId) {
        return skillService.listMyReferences(userId);
    }

    /**
     * 指定 Skill 的引用者列表。
     */
    @GetMapping("/skills/{id}/referencers")
    public List<String> referencers(@PathVariable Long id) {
        return skillService.listReferencers(id);
    }

    // ==================== 用户禁用 (§12.4.2) ====================

    /**
     * 用户禁用 Skill(幂等)。
     */
    @PostMapping("/skills/{id}/disable")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void disable(@PathVariable Long id, @RequestHeader("X-User-Id") String userId) {
        skillService.disable(id, userId);
    }

    /**
     * 取消禁用(幂等)。
     */
    @DeleteMapping("/skills/{id}/disable")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void enable(@PathVariable Long id, @RequestHeader("X-User-Id") String userId) {
        skillService.enable(id, userId);
    }

    /**
     * 禁用状态。
     */
    @GetMapping("/skills/{id}/disable")
    public DisableStatus disableStatus(@PathVariable Long id, @RequestHeader("X-User-Id") String userId) {
        return new DisableStatus(skillService.isDisabled(id, userId));
    }

    // ==================== 发布审批 (§12.4.1) ====================

    /**
     * 申请发布:仅 Skill 所有者可提交,请求体指定发布目标。
     */
    @PostMapping("/skills/{id}/publish")
    public Map<String, Long> submitPublish(@PathVariable Long id,
                                           @RequestBody PublishRequest req,
                                           @RequestHeader("X-User-Id") String userId) {
        Long publishId = skillService.submitPublish(id, req.targetType(), req.targetId(), req.targetName(), userId);
        return Map.of("publishId", publishId);
    }

    /**
     * 查询指定 Skill 的全部发布记录(含 APPROVED 和 PENDING)。
     */
    @GetMapping("/skills/{id}/publishes")
    public List<SkillPublish> publishes(@PathVariable Long id) {
        return skillService.listPublishesBySkillId(id);
    }

    /**
     * 查询当前用户可选的发布目标(按维度类型分组,支持级联选择)。
     */
    @GetMapping("/skills/publish-targets")
    public List<PublishTargetGroup> publishTargets(@RequestHeader("X-User-Id") String userId) {
        return mockOrgService.getUserOrgs(userId).stream()
                .collect(java.util.stream.Collectors.groupingBy(MockOrgService.OrgRef::orgType))
                .entrySet().stream()
                .map(e -> new PublishTargetGroup(
                        e.getKey(),
                        mockOrgService.getTypeLabel(e.getKey()),
                        e.getValue().stream()
                                .map(o -> new OrgTarget(o.orgType(), o.orgId(),
                                        mockOrgService.getDisplayName(o.orgType(), o.orgId()),
                                        mockOrgService.getFullDimensionLabel(o.orgType(), o.orgId())))
                                .toList()))
                .toList();
    }

    /**
     * 审批通过:仅当前审批人可操作。
     */
    @PostMapping("/publish/{id}/approve")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void approvePublish(@PathVariable Long id,
                               @RequestBody ApproveRequest req,
                               @RequestHeader("X-User-Id") String userId) {
        skillService.approvePublish(id, userId, req.comment());
    }

    /**
     * 审批退回:仅当前审批人可操作。
     */
    @PostMapping("/publish/{id}/reject")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void rejectPublish(@PathVariable Long id,
                              @RequestBody ApproveRequest req,
                              @RequestHeader("X-User-Id") String userId) {
        skillService.rejectPublish(id, userId, req.comment());
    }

    /**
     * 待我审批列表。
     */
    @GetMapping("/publish/pending")
    public List<SkillPublish> pendingApprovals(@RequestHeader("X-User-Id") String userId) {
        return skillService.pendingPublishesForApprover(userId);
    }

    /**
     * 我已审批列表(APPROVED/REJECTED)。
     */
    @GetMapping("/publish/history")
    public List<SkillPublish> approvalHistory(@RequestHeader("X-User-Id") String userId) {
        return skillService.historyPublishesForApprover(userId);
    }

    /**
     * 指定发布记录的审批历史。
     */
    @GetMapping("/publish/{id}/approvals")
    public List<SkillApproval> publishApprovals(@PathVariable Long id) {
        return skillService.publishApprovalHistory(id);
    }

    // ==================== 变更草稿 (§12.4.3) ====================

    /**
     * 查看指定 Skill 的当前草稿;无 PENDING 草稿时 body 为 null(200)。
     */
    @GetMapping("/skills/{id}/draft")
    public ResponseEntity<SkillDraft> currentDraft(@PathVariable Long id) {
        return ResponseEntity.ok(skillService.getCurrentDraft(id));
    }

    /**
     * 审批通过:仅当前审批人(或签)可操作。
     */
    @PostMapping("/skills/{id}/draft/approve")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void approveDraft(@PathVariable Long id,
                             @RequestBody ApproveRequest req,
                             @RequestHeader("X-User-Id") String userId) {
        SkillDraft draft = skillService.getCurrentDraft(id);
        if (draft == null) {
            throw new IllegalStateException("DraftNotFound: " + id);
        }
        skillService.approveDraft(draft.getId(), userId, req.comment());
    }

    /**
     * 审批退回:仅当前审批人(或签)可操作。
     */
    @PostMapping("/skills/{id}/draft/reject")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void rejectDraft(@PathVariable Long id,
                            @RequestBody ApproveRequest req,
                            @RequestHeader("X-User-Id") String userId) {
        SkillDraft draft = skillService.getCurrentDraft(id);
        if (draft == null) {
            throw new IllegalStateException("DraftNotFound: " + id);
        }
        skillService.rejectDraft(draft.getId(), userId, req.comment());
    }

    /**
     * 待我审批的草稿列表。
     */
    @GetMapping("/draft/pending")
    public List<SkillDraft> pendingDrafts(@RequestHeader("X-User-Id") String userId) {
        return skillService.pendingDraftsForApprover(userId);
    }

    // ==================== Skill 文件附件引用 ====================

    /**
     * 获取 Skill 引用的文件列表。
     */
    @GetMapping("/skills/{id}/files")
    public List<SkillFileReferenceItem> skillFiles(@PathVariable Long id) {
        return skillService.listSkillFiles(id);
    }

    /**
     * Skill 引用一个文件。
     */
    @PostMapping("/skills/{id}/files")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void addFileReference(@PathVariable Long id,
                                 @RequestBody SkillFileReferenceRequest req,
                                 @RequestHeader("X-User-Id") String userId) {
        skillService.addFileReference(id, req.fileId(), req.referenceType());
    }

    /**
     * Skill 取消引用一个文件。
     */
    @DeleteMapping("/skills/{id}/files/{fileId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeFileReference(@PathVariable Long id, @PathVariable Long fileId,
                                    @RequestHeader("X-User-Id") String userId) {
        skillService.removeFileReference(id, fileId);
    }

    // ==================== 内嵌请求/响应体 ====================

    /** 发布申请请求体。 */
    public record PublishRequest(String targetType, String targetId, String targetName) {}

    /** 审批操作请求体,comment 可空。 */
    public record ApproveRequest(String comment) {}

    /** 禁用状态响应体。 */
    public record DisableStatus(boolean disabled) {}

    /** 发布目标选项。 */
    public record OrgTarget(String orgType, String orgId, String displayName, String fullLabel) {}

    /** 发布目标分组(按维度类型)。 */
    public record PublishTargetGroup(String orgType, String typeLabel, List<OrgTarget> targets) {}
}
