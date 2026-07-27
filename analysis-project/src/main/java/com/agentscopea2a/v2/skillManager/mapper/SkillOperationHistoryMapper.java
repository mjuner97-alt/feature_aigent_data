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

import com.agentscopea2a.v2.skillManager.entity.SkillOperationHistory;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * Skill 操作历史 Mapper - 维护 {@code skill_operation_history} 表的审计快照写入与按技能/操作人查询。
 * 包路径受 {@code MySQLConfig.@MapperScan(basePackages = "com.agentscopea2a.mapper.mysql")} 约束。
 */
@Mapper
public interface SkillOperationHistoryMapper {

    int insert(SkillOperationHistory history);

    List<SkillOperationHistory> selectBySkillId(@Param("skillId") Long skillId);

    List<SkillOperationHistory> selectByOperator(@Param("operator") String operator);
}
