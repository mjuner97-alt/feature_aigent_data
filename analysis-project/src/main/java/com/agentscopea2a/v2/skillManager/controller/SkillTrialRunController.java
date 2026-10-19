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

import com.agentscopea2a.v2.skillManager.entity.SkillTrialRun;
import com.agentscopea2a.v2.skillManager.service.SkillTrialRunService;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Skill 试运行 REST 接口。
 *
 * <p>问数本身由前端直调 {@code /ai/chat}(与 /chat 页共用链路),流结束后前端调
 * POST 本接口落一条试运行记录;GET 返回该 Skill 的历史记录供详情页展示。
 * userId 经 X-User-Id 请求头传入(同 SkillManageController 口径)。</p>
 */
@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*", maxAge = 3600)
public class SkillTrialRunController {

    private final SkillTrialRunService trialRunService;

    public SkillTrialRunController(SkillTrialRunService trialRunService) {
        this.trialRunService = trialRunService;
    }

    /** 记录请求体:会话ID + 问数话术(skillName 由后端按 skillId 回填)。 */
    public record TrialRunRecordRequest(String conversationId, String question) {}

    /**
     * 记录一次试运行(POST /api/skills/{id}/trial-runs)。
     */
    @PostMapping("/skills/{id}/trial-runs")
    public SkillTrialRun record(@PathVariable Long id,
                                @RequestBody TrialRunRecordRequest req,
                                @RequestHeader("X-User-Id") String userId) {
        return trialRunService.record(id, req.conversationId(), req.question(), userId);
    }

    /**
     * 查询该 Skill 的历史试运行记录(GET /api/skills/{id}/trial-runs?limit=20,倒序)。
     *
     * @param afterSkillUpdate 为 true 时仅返回 skill 最后修改时间之后的记录(试运行结果查看用)
     */
    @GetMapping("/skills/{id}/trial-runs")
    public List<SkillTrialRun> list(@PathVariable Long id,
                                    @RequestParam(name = "limit", defaultValue = "20") int limit,
                                    @RequestParam(name = "afterSkillUpdate", defaultValue = "false") boolean afterSkillUpdate,
                                    @RequestHeader("X-User-Id") String userId) {
        return trialRunService.list(id, userId, limit, afterSkillUpdate);
    }
}
