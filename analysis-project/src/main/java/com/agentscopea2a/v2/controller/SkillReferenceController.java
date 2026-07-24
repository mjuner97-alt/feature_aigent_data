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

import com.agentscopea2a.v2.service.SkillReferenceService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 引用 REST 接口。撑"我使用的 Skill"视图。userId 经 X-User-Id 请求头传入。
 */
@RestController
@RequestMapping("/api/skills")
@CrossOrigin(origins = "*", maxAge = 3600)
public class SkillReferenceController {

    private final SkillReferenceService referenceService;

    public SkillReferenceController(SkillReferenceService referenceService) {
        this.referenceService = referenceService;
    }

    @PostMapping("/{id}/reference")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reference(@PathVariable Long id, @RequestHeader("X-User-Id") String userId) {
        referenceService.reference(id, userId);
    }

    @DeleteMapping("/{id}/reference")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unreference(@PathVariable Long id, @RequestHeader("X-User-Id") String userId) {
        referenceService.unreference(id, userId);
    }

    @GetMapping("/my-references")
    public List<Long> myReferences(@RequestHeader("X-User-Id") String userId) {
        return referenceService.listMine(userId);
    }
}
