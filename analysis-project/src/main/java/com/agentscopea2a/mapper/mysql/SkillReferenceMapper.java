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

import com.agentscopea2a.entity.SkillReference;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Set;

/**
 * 引用关系 Mapper。包路径受 {@code MySQLConfig.@MapperScan} 约束。
 */
@Mapper
public interface SkillReferenceMapper {

    int insert(SkillReference ref);

    int deleteByCreatorTarget(@Param("creator") String creator, @Param("skillId") Long skillId);

    boolean existsByCreatorTarget(@Param("creator") String creator, @Param("skillId") Long skillId);

    /** 当前用户引用过的 skillId 列表(我使用的)。 */
    List<Long> selectSkillIdsByCreator(@Param("creator") String creator);

    /** 当前用户在给定集合中已引用的 skillId(列表行 used 标记批量计算)。 */
    Set<Long> selectUsedSkillIds(@Param("creator") String creator, @Param("ids") List<Long> ids);
}
