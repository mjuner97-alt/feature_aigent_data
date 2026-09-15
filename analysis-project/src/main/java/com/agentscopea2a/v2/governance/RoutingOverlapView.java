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

/** 重叠对只读视图 (HTTP 合约), 不暴露内部 record。 */
public record RoutingOverlapView(
        String skillName,
        String skillOwnerUserId,
        String skillSummary,
        String toolId,
        String toolType,
        String level,
        List<String> topicTagOverlap,
        double cosine,
        boolean aliasHit,
        boolean toolIdLiteralInDescription,
        String suggestion) {}
