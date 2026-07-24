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
package com.agentscopea2a.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Skill 列表查询参数。作 MyBatis {@code parameterType},用 Lombok {@code @Data}
 * (getter 解析 OGNL);{@code effectiveXxx()} 提供默认值供 SQL 的 LIMIT/OFFSET。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SkillListQuery {
    private String view;      // all|used|liked|created|popular
    private String sort;      // likes|updated|name
    private String category;
    private String tag;
    private String keyword;
    private Integer limit;
    private Integer offset;
    private String userId;    // 用于 used/liked/created 视图与 liked/used 批量标记

    public String getEffectiveView() { return view == null ? "all" : view; }
    public String getEffectiveSort() { return sort == null ? "likes" : sort; }
    public int getEffectiveLimit() { return limit == null ? 20 : Math.min(limit, 100); }
    public int getEffectiveOffset() { return offset == null ? 0 : Math.max(offset, 0); }
}
