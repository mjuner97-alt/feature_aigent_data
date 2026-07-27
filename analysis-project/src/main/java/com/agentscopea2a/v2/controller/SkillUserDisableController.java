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

import com.agentscopea2a.v2.service.SkillUserDisableService;
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

/**
 * 用户禁用 REST 接口(幂等)。userId 经 X-User-Id 请求头传入。对应 spec §12.4.2。
 */
@RestController
@RequestMapping("/api/skills")
@CrossOrigin(origins = "*", maxAge = 3600)
public class SkillUserDisableController {

    private final SkillUserDisableService disableService;

    public SkillUserDisableController(SkillUserDisableService disableService) {
        this.disableService = disableService;
    }

    @PostMapping("/{id}/disable")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void disable(@PathVariable Long id, @RequestHeader("X-User-Id") String userId) {
        disableService.disable(id, userId);
    }

    @DeleteMapping("/{id}/disable")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void enable(@PathVariable Long id, @RequestHeader("X-User-Id") String userId) {
        disableService.enable(id, userId);
    }

    @GetMapping("/{id}/disable")
    public DisableStatus status(@PathVariable Long id, @RequestHeader("X-User-Id") String userId) {
        return new DisableStatus(disableService.isDisabled(id, userId));
    }

    /** 禁用状态响应体。 */
    public record DisableStatus(boolean disabled) {
    }
}
