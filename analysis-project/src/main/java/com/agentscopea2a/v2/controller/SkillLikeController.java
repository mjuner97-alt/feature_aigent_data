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

import com.agentscopea2a.dto.LikeStatus;
import com.agentscopea2a.v2.service.SkillLikeService;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 点赞 REST 接口(幂等 toggle)。userId 经 X-User-Id 请求头传入。
 */
@RestController
@RequestMapping("/api/skills")
@CrossOrigin(origins = "*", maxAge = 3600)
public class SkillLikeController {

    private final SkillLikeService likeService;

    public SkillLikeController(SkillLikeService likeService) {
        this.likeService = likeService;
    }

    @PostMapping("/{id}/like")
    public LikeStatus like(@PathVariable Long id, @RequestHeader("X-User-Id") String userId) {
        return likeService.like(id, userId);
    }

    @DeleteMapping("/{id}/like")
    public LikeStatus unlike(@PathVariable Long id, @RequestHeader("X-User-Id") String userId) {
        return likeService.unlike(id, userId);
    }

    @GetMapping("/{id}/like")
    public LikeStatus status(@PathVariable Long id, @RequestHeader("X-User-Id") String userId) {
        return likeService.getStatus(id, userId);
    }
}
