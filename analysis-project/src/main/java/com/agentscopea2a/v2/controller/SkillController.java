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

import com.agentscopea2a.dto.SkillListItem;
import com.agentscopea2a.dto.SkillListQuery;
import com.agentscopea2a.entity.Skill;
import com.agentscopea2a.v2.service.SkillService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Skill 管理 REST 接口:CRUD + 列表(视图/排序/筛选/分页)。userId 经 X-User-Id 请求头传入。
 */
@RestController
@RequestMapping("/api/skills")
@CrossOrigin(origins = "*", maxAge = 3600)
public class SkillController {

    private final SkillService skillService;

    public SkillController(SkillService skillService) {
        this.skillService = skillService;
    }

    @GetMapping
    public List<SkillListItem> list(
            @RequestParam(name = "view", required = false) String view,
            @RequestParam(name = "sort", required = false) String sort,
            @RequestParam(name = "category", required = false) String category,
            @RequestParam(name = "tag", required = false) String tag,
            @RequestParam(name = "keyword", required = false) String keyword,
            @RequestParam(name = "limit", required = false) Integer limit,
            @RequestParam(name = "offset", required = false) Integer offset,
            @RequestHeader("X-User-Id") String userId) {
        return skillService.list(new SkillListQuery(view, sort, category, tag, keyword, limit, offset, userId));
    }

    @PostMapping
    public Skill create(@RequestBody Skill skill, @RequestHeader("X-User-Id") String userId) {
        return skillService.create(skill, userId);
    }

    @GetMapping("/get")
    public Skill get(@RequestParam(name = "id") Long id) {
        return skillService.get(id);
    }

    @PutMapping
    public Skill update(@RequestParam(name = "id") Long id, @RequestBody Skill patch,
                        @RequestHeader("X-User-Id") String userId) {
        return skillService.update(id, patch, userId);
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@RequestParam(name = "id") Long id, @RequestHeader("X-User-Id") String userId) {
        skillService.delete(id, userId);
    }
}
