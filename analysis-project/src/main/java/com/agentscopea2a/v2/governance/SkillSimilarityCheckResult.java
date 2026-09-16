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

import java.util.List;

/** Skill 描述相似检测结果 (创建/更新前的校验与提醒)。 */
public record SkillSimilarityCheckResult(boolean degraded, List<SkillSimilarityMatch> matches) {

    /** 单个相似 Skill。PRIVATE 项只暴露 name/description/ownerUserId (方案 §2.4)。 */
    public record SkillSimilarityMatch(
            Long skillId,
            String name,
            String description,
            String ownerUserId,
            String visibility,
            double similarity,
            List<String> evidence) {}
}
