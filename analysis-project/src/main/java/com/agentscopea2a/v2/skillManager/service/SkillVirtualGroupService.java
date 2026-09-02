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
package com.agentscopea2a.v2.skillManager.service;

import com.agentscopea2a.v2.skillManager.entity.SkillVirtualGroup;
import com.agentscopea2a.v2.skillManager.entity.SkillVirtualGroupDef;
import com.agentscopea2a.v2.skillManager.mapper.SkillVirtualGroupMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 虚拟组 Service - 虚拟组(组名+userid)的建组/删组/成员增删。
 *
 * <p>虚拟组是私有 Skill 授权({@code skill_visible_grant} 的 {@code VIRTUAL_GROUP} 类型)
 * 的授权对象之一:把一批用户编入虚拟组后,owner 授权该组即等同逐个授权组内成员,
 * 授权即时生效、不走审批。组本身与组织架构无关,由用户自由创建。
 */
@Service
public class SkillVirtualGroupService {

    private final SkillVirtualGroupMapper virtualGroupMapper;
    private final MockOrgService mockOrgService;

    public SkillVirtualGroupService(SkillVirtualGroupMapper virtualGroupMapper,
                                    MockOrgService mockOrgService) {
        this.virtualGroupMapper = virtualGroupMapper;
        this.mockOrgService = mockOrgService;
    }

    /** 组信息(组名 + 成员列表),管理页展示用。 */
    public record GroupDetail(String groupName, List<Member> members) {}

    /** 成员项(userId + 姓名展示)。 */
    public record Member(String userId) {}

    /** 全部虚拟组(组头表为准,按组名字典序,含空组)。 */
    public List<GroupDetail> listGroups() {
        List<SkillVirtualGroup> rows = virtualGroupMapper.selectAll();
        Map<String, List<Member>> byGroup = new LinkedHashMap<>();
        for (SkillVirtualGroup r : rows) {
            byGroup.computeIfAbsent(r.getGroupName(), k -> new ArrayList<>())
                    .add(new Member(r.getUserId()));
        }
        return virtualGroupMapper.selectAllGroupDefs().stream()
                .map(def -> new GroupDetail(def.getGroupName(),
                        byGroup.getOrDefault(def.getGroupName(), List.of())))
                .toList();
    }

    /** 组内成员列表。组不存在返回空列表。 */
    public List<SkillVirtualGroup> listMembers(String groupName) {
        return virtualGroupMapper.selectMembers(groupName);
    }

    /**
     * 建组:写组头表(组名主键,重名报"VirtualGroupNameExists"),空组合法;
     * 若带首个成员则一并写入。组的存在性与成员行解耦。
     * 组名不得与真实统计组(GROUP)重名:授权下拉里"小组"与"虚拟组"同名会混淆授权对象。
     */
    @Transactional("gaussCustomerTransactionManager")
    public void createGroup(String groupName, String firstUserId, String operator) {
        validateGroupName(groupName);
        if (mockOrgService.orgExists("GROUP", groupName)) {
            throw new IllegalStateException("VirtualGroupNameConflictWithOrg: " + groupName
                    + " 与真实统计组重名,请换一个组名");
        }
        try {
            virtualGroupMapper.insertGroupDef(SkillVirtualGroupDef.builder()
                    .groupName(groupName).createdBy(operator)
                    .createdAt(LocalDateTime.now()).build());
        } catch (DuplicateKeyException e) {
            throw new IllegalStateException("VirtualGroupNameExists: " + groupName);
        }
        if (firstUserId != null && !firstUserId.isBlank()) {
            addMemberInternal(groupName, firstUserId, operator);
        }
    }

    /** 加成员(幂等)。组必须存在;成员必须存在于人员表(developer_pl_person_info)。 */
    @Transactional("gaussCustomerTransactionManager")
    public void addMember(String groupName, String userId, String operator) {
        validateGroupName(groupName);
        if (!virtualGroupMapper.existsGroup(groupName)) {
            throw new IllegalStateException("VirtualGroupNotFound: " + groupName);
        }
        addMemberInternal(groupName, userId, operator);
    }

    /** 移除成员。移除最后一个成员后组仍在(组由组头表定义)。 */
    @Transactional("gaussCustomerTransactionManager")
    public void removeMember(String groupName, String userId) {
        virtualGroupMapper.deleteMember(groupName, userId);
    }

    /**
     * 删除整个组。被私有授权(VIRTUAL_GROUP)引用时阻止删除,
     * 避免留下悬空授权(组没了,授权还在,永不命中)。
     */
    @Transactional("gaussCustomerTransactionManager")
    public void deleteGroup(String groupName) {
        validateGroupName(groupName);
        long referenced = virtualGroupMapper.countGrantsReferencingGroup(groupName);
        if (referenced > 0) {
            throw new IllegalStateException("VirtualGroupReferenced: " + groupName
                    + " 被 " + referenced + " 条私有授权引用,请先取消相关授权");
        }
        virtualGroupMapper.deleteGroup(groupName);
        virtualGroupMapper.deleteGroupDef(groupName);
    }

    /** 判断组是否存在(供授权目标校验)。 */
    public boolean existsGroup(String groupName) {
        return virtualGroupMapper.existsGroup(groupName);
    }

    private void addMemberInternal(String groupName, String userId, String operator) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalStateException("VirtualGroupInvalidMember: empty userId");
        }
        if (!mockOrgService.userExists(userId)) {
            throw new IllegalStateException("VirtualGroupInvalidMember: 人员不存在 " + userId);
        }
        try {
            virtualGroupMapper.insertMember(SkillVirtualGroup.builder()
                    .groupName(groupName).userId(userId).createdBy(operator)
                    .createdAt(LocalDateTime.now()).build());
        } catch (DuplicateKeyException e) {
            // 幂等:已在组内
        }
    }

    private void validateGroupName(String groupName) {
        if (groupName == null || groupName.isBlank()) {
            throw new IllegalStateException("VirtualGroupInvalidName: empty group name");
        }
    }
}
