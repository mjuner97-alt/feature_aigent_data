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
package com.agentscopea2a.v2.skillManager.dto;

import com.agentscopea2a.v2.skillManager.entity.Skill;

import java.time.LocalDateTime;

/**
 * Skill 列表行 DTO(含点赞数、liked/used/available/disabled 标记、热门榜排名、维度、所有者姓名)。
 *
 * <p>三个行级标记的语义(列表徽章由它们决定,见 SkillManage.vue badgeClass):
 * <ul>
 *   <li>{@code used} —— 当前用户是否"已使用" = 显式引用(skill_reference 有记录)
 *       ∪ 自己创建(owner) ∪ 所属维度已发布(默认可用,无引用记录)。维度内 skill 默认为 used=true。</li>
 *   <li>{@code disabled} —— 当前用户是否已主动禁用该 skill(用户禁用表有记录)。</li>
 *   <li>{@code available} —— 是否真正可用 = used && !disabled。被禁用后即使 used 也不可用。</li>
 * </ul>
 *
 * <p>注意:这里的 {@code used} 与详情页"引用/取消引用"按钮(referenced)不是一回事——
 * 后者只代表 skill_reference 表里的显式引用记录,不包含维度默认可用与所有者身份。
 *
 * <p>{@code dimension} 从发布记录派生,默认 PERSONAL;多维度发布时取最高级
 * (COMPANY > PRODUCT_LINE > DEPARTMENT > GROUP);{@code ownerName} 为所有者姓名
 * (从 developer_pl_person_info 解析),缺失时为 null,前端回退到 ownerUserId。
 */
public record SkillListItem(
        Long id,                  // skill 主键
        String name,              // skill 名称
        String description,       // skill 描述
        String category,          // 分类(保留字段,前端不再使用)
        String tags,              // 标签(逗号分隔,保留字段)
        String ownerUserId,       // 所有者统一认证号
        String ownerName,         // 所有者姓名(从 developer_pl_person_info 解析,缺失为 null,前端回退 ownerUserId)
        long likeCount,           // 点赞数(skill_manage.like_count)
        boolean liked,            // 当前用户是否已点赞(行标记,按本页 skillId 集合批量计算)
        boolean used,             // 当前用户是否"已使用"= 显式引用 ∪ 自己创建 ∪ 所属维度已发布(默认可用,无引用记录)
        boolean available,        // 是否可用 = used && !disabled(被禁用后即使 used 也不可用)
        boolean disabled,         // 当前用户是否已主动禁用(行标记,skill_user_disable 表有记录)
        Integer rank,             // 热门榜排名(仅 popular 视图有值,其余视图为 null)
        LocalDateTime updatedAt,  // 最后更新时间
        String dimension          // 维度标签:PERSONAL/GROUP/DEPARTMENT/PRODUCT_LINE/COMPANY,从已审批发布记录派生
) {
    public static SkillListItem of(Skill s, boolean liked, boolean used, Integer rank) {
        return of(s, liked, used, true, false, rank, "PERSONAL", null);
    }

    public static SkillListItem of(Skill s, boolean liked, boolean used, boolean available, boolean disabled, Integer rank) {
        return of(s, liked, used, available, disabled, rank, "PERSONAL", null);
    }

    public static SkillListItem of(Skill s, boolean liked, boolean used, boolean available, boolean disabled, Integer rank, String dimension) {
        return of(s, liked, used, available, disabled, rank, dimension, null);
    }

    public static SkillListItem of(Skill s, boolean liked, boolean used, boolean available, boolean disabled, Integer rank, String dimension, String ownerName) {
        return new SkillListItem(
                s.getId(), s.getName(), s.getDescription(), s.getCategory(), s.getTags(),
                s.getOwnerUserId(), ownerName,
                s.getLikeCount() == null ? 0L : s.getLikeCount(),
                liked, used, available, disabled, rank, s.getUpdatedAt(), dimension);
    }
}
