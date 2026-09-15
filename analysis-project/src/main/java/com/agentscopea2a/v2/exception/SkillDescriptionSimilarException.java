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
package com.agentscopea2a.v2.exception;

import com.agentscopea2a.v2.governance.SkillSimilarityCheckResult;

/**
 * 创建/更新 Skill 时命中高相似描述。
 * HTTP 409 + 相似列表 (含 ownerUserId), 前端转提示框, 用户仅能返回修改 (无放行路径)。
 */
public class SkillDescriptionSimilarException extends RuntimeException {

    private final transient SkillSimilarityCheckResult result;

    public SkillDescriptionSimilarException(SkillSimilarityCheckResult result) {
        super("SkillDescriptionSimilar: " + result.matches().size() + " 个相近描述的 Skill");
        this.result = result;
    }

    public SkillSimilarityCheckResult result() {
        return result;
    }
}
