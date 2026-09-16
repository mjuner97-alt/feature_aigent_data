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

/**
 * 治理检测的 Skill 描述权威来源 (skill_manage 表, ACTIVE 且未软删)。
 * 独立成接口便于单测替换; 描述漂移治理决策见
 * docs/skill-tool-overlap-and-description-similarity-plan.md §1.5。
 */
public interface SkillDescriptionSource {

    record SkillDescriptionRow(
            Long id,
            String name,
            String description,
            String ownerUserId,
            String retrievalName,
            String visibility) {}

    /** 全部未删除的 Skill (含他人 PRIVATE, 治理有意全量比较, 见方案 §2.4)。 */
    List<SkillDescriptionRow> allActiveSkills();
}
