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
package com.agentscopea2a.v2.controller;

import com.agentscopea2a.entity.SkillDraft;
import com.agentscopea2a.v2.service.SkillDraftService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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

/**
 * 变更草稿 REST 接口(§12.4.3)。userId 经 X-User-Id 请求头传入。
 *
 * <p>路径分两段:草稿审批挂在 {@code /api/skills/{id}/draft} 下,
 * 待审列表挂在 {@code /api/draft/...} 下,故类级前缀仅取 {@code /api}。
 * 审批接口的 {@code {id}} 为 skillId,内部解析为当前 PENDING 草稿 id。
 */
@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*", maxAge = 3600)
public class SkillDraftController {

    private final SkillDraftService service;

    public SkillDraftController(SkillDraftService service) {
        this.service = service;
    }

    /**
     * 查看指定 Skill 的当前草稿;无 PENDING 草稿时 body 为 null(200)。
     */
    @GetMapping("/skills/{id}/draft")
    public ResponseEntity<SkillDraft> currentDraft(@PathVariable Long id) {
        return ResponseEntity.ok(service.getCurrentDraft(id));
    }

    /**
     * 审批通过:仅当前审批人(或签)可操作。
     */
    @PostMapping("/skills/{id}/draft/approve")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void approve(@PathVariable Long id,
                        @RequestBody ApproveRequest req,
                        @RequestHeader("X-User-Id") String userId) {
        SkillDraft draft = service.getCurrentDraft(id);
        if (draft == null) {
            throw new IllegalStateException("DraftNotFound: " + id);
        }
        service.approve(draft.getId(), userId, req.comment());
    }

    /**
     * 审批退回:仅当前审批人(或签)可操作。
     */
    @PostMapping("/skills/{id}/draft/reject")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reject(@PathVariable Long id,
                       @RequestBody ApproveRequest req,
                       @RequestHeader("X-User-Id") String userId) {
        SkillDraft draft = service.getCurrentDraft(id);
        if (draft == null) {
            throw new IllegalStateException("DraftNotFound: " + id);
        }
        service.reject(draft.getId(), userId, req.comment());
    }

    /**
     * 待我审批的草稿列表。
     */
    @GetMapping("/draft/pending")
    public List<SkillDraft> pending(@RequestHeader("X-User-Id") String userId) {
        return service.pendingForApprover(userId);
    }

    /** 审批操作请求体,comment 可空。 */
    public record ApproveRequest(String comment) {}
}
