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
 * Skill-Skill 重叠对视图 (docs/history/skill-skill-overlap-plan.md §3.2)。HTTP 合约, 只读派生数据。
 *
 * @param activeA/activeB 两侧真实 active 状态 (启动自愈与启用互斥按此过滤; 页面如实展示)
 */
public record SkillSkillOverlapView(
        String skillNameA,
        String ownerA,
        String skillNameB,
        String ownerB,
        String level,
        List<String> domainTagsA,
        List<String> domainTagsB,
        List<String> keywordsA,
        List<String> keywordsB,
        List<String> keywordOverlap,
        double cosine,
        boolean cosineKnown,
        List<String> differingLayers,
        String suggestion,
        boolean activeA,
        boolean activeB) {}
