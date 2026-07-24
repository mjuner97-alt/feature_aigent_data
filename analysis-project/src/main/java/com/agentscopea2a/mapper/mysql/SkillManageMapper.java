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

import com.agentscopea2a.dto.SkillListQuery;
import com.agentscopea2a.entity.Skill;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * Skill 管理 Mapper - 维护 {@code skill_manage} 表的 CRUD、点赞计数原子操作与列表查询。
 * 包路径受 {@code MySQLConfig.@MapperScan(basePackages = "com.agentscopea2a.mapper.mysql")} 约束。
 */
@Mapper
public interface SkillManageMapper {

    int insert(Skill skill);

    Skill selectById(@Param("id") Long id);

    int update(Skill skill);

    int softDelete(@Param("id") Long id);

    boolean existsByName(@Param("name") String name);

    Long selectLikeCount(@Param("id") Long id);

    int incrementLikeCount(@Param("id") Long id);

    int decrementLikeCount(@Param("id") Long id);

    List<Skill> selectByIds(@Param("ids") List<Long> ids);

    /** 列表查询:按 view/sort/category/tag/keyword 过滤 + 分页。 */
    List<Skill> selectList(SkillListQuery q);
}
