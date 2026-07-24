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

import com.agentscopea2a.entity.Skill;

import java.time.LocalDateTime;

/**
 * Skill 列表行 DTO(含点赞数、liked/used/available 标记、热门榜排名)。
 * 本计划 {@code available} 恒为 true(可用性计算留后续计划)。
 */
public record SkillListItem(
        Long id, String name, String description, String category, String tags,
        String ownerUserId, long likeCount, boolean liked, boolean used,
        boolean available, Integer rank, LocalDateTime updatedAt
) {
    public static SkillListItem of(Skill s, boolean liked, boolean used, Integer rank) {
        return new SkillListItem(
                s.getId(), s.getName(), s.getDescription(), s.getCategory(), s.getTags(),
                s.getOwnerUserId(),
                s.getLikeCount() == null ? 0L : s.getLikeCount(),
                liked, used, true, rank, s.getUpdatedAt());
    }
}
