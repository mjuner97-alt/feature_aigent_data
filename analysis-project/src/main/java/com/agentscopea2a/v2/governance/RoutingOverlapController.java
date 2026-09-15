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
package com.agentscopea2a.v2.governance;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Skill-Tool 重叠检测治理视图 (管理员)。只读派生数据, 不做任何处置;
 * 登录校验沿用现有管理页 (X-User-Id 头由前端网关注入)。
 */
@RestController
@RequestMapping("/api/routing-overlap")
public class RoutingOverlapController {

    private final SkillToolOverlapService overlapService;

    public RoutingOverlapController(SkillToolOverlapService overlapService) {
        this.overlapService = overlapService;
    }

    public record OverlapListResponse(
            boolean degraded,
            int total,
            List<RoutingOverlapView> items) {}

    @GetMapping
    public OverlapListResponse list(
            @RequestParam(name = "level", required = false) String level,
            @RequestParam(name = "skillName", required = false) String skillName,
            @RequestParam(name = "toolId", required = false) String toolId,
            @RequestParam(name = "limit", defaultValue = "200") int limit,
            @RequestParam(name = "offset", defaultValue = "0") int offset) {
        SkillToolOverlapService.OverlapReport report = overlapService.report();
        List<RoutingOverlapView> items = report.items().stream()
                .filter(item -> level == null || level.isBlank() || item.level().equalsIgnoreCase(level))
                .filter(item -> skillName == null || skillName.isBlank()
                        || item.skillName().equals(skillName))
                .filter(item -> toolId == null || toolId.isBlank() || item.toolId().equals(toolId))
                .toList();
        int from = Math.max(0, Math.min(offset, items.size()));
        int to = Math.min(items.size(), from + Math.max(1, Math.min(limit, 500)));
        return new OverlapListResponse(report.degraded(), items.size(), items.subList(from, to));
    }

    @GetMapping("/summary")
    public SkillToolOverlapService.OverlapSummary summary() {
        return overlapService.summary();
    }
}
