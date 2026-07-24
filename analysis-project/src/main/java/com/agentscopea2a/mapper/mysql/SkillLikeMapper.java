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
package com.agentscopea2a.mapper.mysql;

import com.agentscopea2a.entity.SkillLike;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Set;

/**
 * 点赞 Mapper。包路径受 {@code MySQLConfig.@MapperScan} 约束。
 */
@Mapper
public interface SkillLikeMapper {

    int insert(SkillLike like);

    SkillLike selectByUserSkill(@Param("userId") String userId, @Param("skillId") Long skillId);

    int deleteByUserSkill(@Param("userId") String userId, @Param("skillId") Long skillId);

    /** 当前用户在给定 skillId 集合中已点赞的 skillId(列表行 liked 标记批量计算)。 */
    Set<Long> selectLikedSkillIds(@Param("userId") String userId, @Param("ids") List<Long> ids);
}
