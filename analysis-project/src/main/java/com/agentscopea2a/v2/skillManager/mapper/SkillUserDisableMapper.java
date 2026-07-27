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
package com.agentscopea2a.v2.skillManager.mapper;

import com.agentscopea2a.v2.skillManager.entity.SkillUserDisable;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Set;

/**
 * Skill 用户禁用 Mapper - 维护 {@code skill_user_disable} 表的禁用关系写入/删除与查询。
 * 包路径受 {@code MySQLConfig.@MapperScan(basePackages = "com.agentscopea2a.mapper.mysql")} 约束。
 */
@Mapper
public interface SkillUserDisableMapper {

    int insert(SkillUserDisable disable);

    int deleteByUserSkill(@Param("userId") String userId, @Param("skillId") Long skillId);

    boolean existsByUserSkill(@Param("userId") String userId, @Param("skillId") Long skillId);

    Set<Long> selectDisabledSkillIds(@Param("userId") String userId, @Param("skillIds") List<Long> skillIds);
}
