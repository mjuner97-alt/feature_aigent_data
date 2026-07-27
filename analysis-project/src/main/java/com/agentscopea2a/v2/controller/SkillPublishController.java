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
package com.agentscopea2a.v2.controller;

import com.agentscopea2a.entity.SkillApproval;
import com.agentscopea2a.entity.SkillPublish;
import com.agentscopea2a.v2.service.MockOrgService;
import com.agentscopea2a.v2.service.SkillPublishService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 发布审批 REST 接口(§12.4.1)。userId 经 X-User-Id 请求头传入。
 *
 * <p>路径分两段:发布申请挂在 {@code /api/skills/{id}/publish} 下,
 * 审批操作挂在 {@code /api/publish/...} 下,故类级前缀仅取 {@code /api}。
 */
@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*", maxAge = 3600)
public class SkillPublishController {

    private final SkillPublishService service;
    private final MockOrgService mockOrgService;

    public SkillPublishController(SkillPublishService service, MockOrgService mockOrgService) {
        this.service = service;
        this.mockOrgService = mockOrgService;
    }

    /**
     * 申请发布:仅 Skill 所有者可提交,请求体指定发布目标。
     */
    @PostMapping("/skills/{id}/publish")
    public Map<String, Long> submitPublish(@PathVariable Long id,
                                           @RequestBody PublishRequest req,
                                           @RequestHeader("X-User-Id") String userId) {
        Long publishId = service.submitPublish(id, req.targetType(), req.targetId(), req.targetName(), userId);
        return Map.of("publishId", publishId);
    }

    /**
     * 查询指定 Skill 的全部发布记录(含 APPROVED 和 PENDING)。
     */
    @GetMapping("/skills/{id}/publishes")
    public List<SkillPublish> publishes(@PathVariable Long id) {
        return service.listBySkillId(id);
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
    public void approve(@PathVariable Long id,
                        @RequestBody ApproveRequest req,
                        @RequestHeader("X-User-Id") String userId) {
        service.approve(id, userId, req.comment());
    }

    /**
     * 审批退回:仅当前审批人可操作。
     */
    @PostMapping("/publish/{id}/reject")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reject(@PathVariable Long id,
                       @RequestBody ApproveRequest req,
                       @RequestHeader("X-User-Id") String userId) {
        service.reject(id, userId, req.comment());
    }

    /**
     * 待我审批列表。
     */
    @GetMapping("/publish/pending")
    public List<SkillPublish> pending(@RequestHeader("X-User-Id") String userId) {
        return service.pendingForApprover(userId);
    }

    /**
     * 指定发布记录的审批历史。
     */
    @GetMapping("/publish/{id}/approvals")
    public List<SkillApproval> approvals(@PathVariable Long id) {
        return service.approvalHistory(id);
    }

    /** 发布申请请求体。 */
    public record PublishRequest(String targetType, String targetId, String targetName) {}

    /** 审批操作请求体,comment 可空。 */
    public record ApproveRequest(String comment) {}

    /** 发布目标选项。 */
    public record OrgTarget(String orgType, String orgId, String displayName, String fullLabel) {}

    /** 发布目标分组(按维度类型)。 */
    public record PublishTargetGroup(String orgType, String typeLabel, List<OrgTarget> targets) {}
}
