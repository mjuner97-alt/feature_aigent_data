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

import com.agentscopea2a.v2.skillManager.entity.SkillVirtualGroup;
import com.agentscopea2a.v2.skillManager.entity.SkillVirtualGroupDef;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 虚拟组 Mapper - 维护 skill_virtual_group(组名+userid)的增删查。
 * 包路径受 {@code GaussConfig.@MapperScan} 约束(与 SkillMapper 同包)。
 */
@Mapper
public interface SkillVirtualGroupMapper {

    /** 插入组头(组名主键,重名时抛 DuplicateKeyException 由调用方转成业务错误)。 */
    int insertGroupDef(SkillVirtualGroupDef groupDef);

    /** 删除组头。 */
    int deleteGroupDef(@Param("groupName") String groupName);

    /** 查询全部组头(管理页列表,含空组)。 */
    List<SkillVirtualGroupDef> selectAllGroupDefs();

    /** 插入一个成员(幂等由唯一索引兜底,并发时抛 DuplicateKeyException 由调用方处理)。 */
    int insertMember(SkillVirtualGroup member);

    /** 删除组内一个成员。 */
    int deleteMember(@Param("groupName") String groupName, @Param("userId") String userId);

    /** 删除整个组(所有成员行)。返回删除行数。 */
    int deleteGroup(@Param("groupName") String groupName);

    /** 查询组内全部成员。 */
    List<SkillVirtualGroup> selectMembers(@Param("groupName") String groupName);

    /** 查询全部成员行(管理页按组名分组展示)。 */
    List<SkillVirtualGroup> selectAll();

    /** 判断组是否存在(以组头表为准,空组也算存在)。 */
    boolean existsGroup(@Param("groupName") String groupName);

    /** 判断用户是否已是指定组成员。 */
    boolean existsMember(@Param("groupName") String groupName, @Param("userId") String userId);

    /**
     * 统计引用了指定虚拟组的私有授权条数(skill_visible_grant 的 VIRTUAL_GROUP 类型)。
     * 删组前检查用:被引用时阻止删除,避免留下悬空授权。
     */
    long countGrantsReferencingGroup(@Param("groupName") String groupName);
}
