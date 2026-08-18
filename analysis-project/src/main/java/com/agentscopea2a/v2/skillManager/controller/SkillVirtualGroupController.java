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
package com.agentscopea2a.v2.skillManager.controller;

import com.agentscopea2a.v2.skillManager.entity.SkillVirtualGroup;
import com.agentscopea2a.v2.skillManager.service.MockOrgService;
import com.agentscopea2a.v2.skillManager.service.SkillVirtualGroupService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 虚拟组管理 REST 接口。userId 经 X-User-Id 请求头传入。
 *
 * <p>虚拟组(组名+userid)是私有 Skill 授权的授权对象之一:
 * owner 在"谁可以看"里按虚拟组授权,组内成员即时可见,不走审批。
 */
@RestController
@RequestMapping("/api/virtual-groups")
@CrossOrigin(origins = "*", maxAge = 3600)
public class SkillVirtualGroupController {

    private final SkillVirtualGroupService virtualGroupService;
    private final MockOrgService mockOrgService;

    public SkillVirtualGroupController(SkillVirtualGroupService virtualGroupService,
                                       MockOrgService mockOrgService) {
        this.virtualGroupService = virtualGroupService;
        this.mockOrgService = mockOrgService;
    }

    /**
     * 全部虚拟组(组名+成员),成员附带姓名供展示。
     */
    @GetMapping
    public List<Map<String, Object>> list() {
        return virtualGroupService.listGroups().stream()
                .map(g -> {
                    Map<String, String> nameMap = mockOrgService
                            .getUserNameMap(g.members().stream().map(SkillVirtualGroupService.Member::userId).toList());
                    List<Map<String, String>> members = g.members().stream()
                            .map(m -> {
                                Map<String, String> item = new HashMap<>();
                                item.put("userId", m.userId());
                                item.put("name", nameMap.getOrDefault(m.userId(), m.userId()));
                                return item;
                            }).toList();
                    return Map.<String, Object>of(
                            "groupName", g.groupName(),
                            "memberCount", members.size(),
                            "members", members);
                }).toList();
    }

    /**
     * 指定组内成员列表。
     */
    @GetMapping("/{name}/members")
    public List<SkillVirtualGroup> members(@PathVariable(name = "name") String groupName) {
        return virtualGroupService.listMembers(groupName);
    }

    /**
     * 建组(可同时带首个成员)。
     */
    @PostMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void create(@RequestBody CreateGroupRequest req,
                       @RequestHeader("X-User-Id") String userId) {
        virtualGroupService.createGroup(req.groupName(), req.firstUserId(), userId);
    }

    /**
     * 删除组(被私有授权引用时返回错误)。
     */
    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@RequestParam(name = "groupName") String groupName) {
        virtualGroupService.deleteGroup(groupName);
    }

    /**
     * 加成员(幂等;成员需存在于人员表)。
     */
    @PostMapping("/{name}/members")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void addMember(@PathVariable(name = "name") String groupName,
                          @RequestBody MemberRequest req,
                          @RequestHeader("X-User-Id") String userId) {
        virtualGroupService.addMember(groupName, req.userId(), userId);
    }

    /**
     * 移除成员。
     */
    @DeleteMapping("/{name}/members")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeMember(@PathVariable(name = "name") String groupName,
                             @RequestParam(name = "userId") String userId) {
        virtualGroupService.removeMember(groupName, userId);
    }

    // ==================== 内嵌请求体 ====================

    /** 建组请求体。 */
    public record CreateGroupRequest(String groupName, String firstUserId) {}

    /** 成员操作请求体。 */
    public record MemberRequest(String userId) {}
}
