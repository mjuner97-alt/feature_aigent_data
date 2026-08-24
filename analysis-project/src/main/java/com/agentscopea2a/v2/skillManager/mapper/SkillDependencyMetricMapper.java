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

import com.agentscopea2a.v2.skillManager.entity.SkillDependencyMetric;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 依赖指标 Mapper（只读）：admin 预置数据，无 insert/update/delete。
 */
@Mapper
public interface SkillDependencyMetricMapper {

    /** 列出启用的指标（前端下拉用） */
    List<SkillDependencyMetric> selectAllEnabled(@Param("keyword") String keyword);

    /** 按业务编码查询（triggerByMetric 入口用） */
    SkillDependencyMetric selectByCode(@Param("code") String code);

    /** 按主键查询（create 校验用） */
    SkillDependencyMetric selectById(@Param("id") Long id);
}
