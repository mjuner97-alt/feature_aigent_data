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

import com.agentscopea2a.dto.UserInfo;
import com.agentscopea2a.v2.auth.entity.DeveloperPlPersonInfo;
import com.agentscopea2a.v2.auth.mapper.DeveloperPlPersonInfoMapper;
import com.agentscopea2a.v2.skillManager.entity.SkillApprover;
import com.agentscopea2a.v2.skillManager.mapper.SkillApproverMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 组织信息服务 - 从 developer_pl_person_info 与 skill_approver 表动态查询用户四维归属
 * (GROUP/DEPARTMENT/PRODUCT_LINE/COMPANY)、组织显示名称、维度标签与审批人映射。
 *
 * <p>历史:本类前身为 {@code MockOrgService}(硬编码静态 Map),现已改造为数据库驱动。
 * 类名暂保留 {@code MockOrgService} 以减少调用方改动,后续可重命名为 {@code OrgService}。
 *
 * <p>orgId 约定:本实现中 orgId 即组织名称(如 "开发一组"/"研发部"/"数据产品线"),
 * 不再使用 group_001 之类的硬编码代号。COMPANY 类型固定 orgId="杭研"。
 */
@Service
public class MockOrgService {

    private static final Logger log = LoggerFactory.getLogger(MockOrgService.class);

    /** COMPANY 维度固定值。 */
    private static final String COMPANY_ORG_ID = "杭研";
    private static final String COMPANY_APPROVAL_SCOPE_NAME = "杭研";

    /** 组织级别前缀标签。 */
    private static final Map<String, String> ORG_TYPE_LABEL = Map.of(
            "GROUP",        "小组",
            "DEPARTMENT",   "部门",
            "PRODUCT_LINE", "产品线",
            "COMPANY",      "杭研"
    );

    private final DeveloperPlPersonInfoMapper personInfoMapper;
    private final SkillApproverMapper skillApproverMapper;

    /** 审批人用户 id 集合缓存(短 TTL,避免每次发布都查全表)。 */
    private volatile Set<String> approverIdsCache;
    private volatile long approverIdsCacheAt;
    private static final long APPROVER_IDS_TTL_MS = 60_000L;

    public MockOrgService(DeveloperPlPersonInfoMapper personInfoMapper,
                          SkillApproverMapper skillApproverMapper) {
        this.personInfoMapper = personInfoMapper;
        this.skillApproverMapper = skillApproverMapper;
    }

    /** 组织引用记录。 */
    public record OrgRef(String orgType, String orgId) {}

    /**
     * 获取用户所属组织列表。从 developer_pl_person_info 查询用户全部记录,
     * 提取去重的 统计组/部门/产品线,并附加 COMPANY 维度。
     */
    public List<OrgRef> getUserOrgs(String userId) {
        if (userId == null || userId.isBlank()) {
            return List.of();
        }
        List<DeveloperPlPersonInfo> records = personInfoMapper.selectByUserId(userId);
        if (records == null || records.isEmpty()) {
            return List.of();
        }
        // 用 LinkedHashMap 去重并保持插入顺序:统计组 -> 部门 -> 产品线 -> 公司
        Map<String, OrgRef> orgMap = new LinkedHashMap<>();
        for (DeveloperPlPersonInfo r : records) {
            String g = r.getStatisticsGroup();
            if (g != null && !g.isBlank()) {
                orgMap.putIfAbsent("GROUP:" + g, new OrgRef("GROUP", g));
            }
        }
        for (DeveloperPlPersonInfo r : records) {
            String d = r.getDepartment();
            if (d != null && !d.isBlank()) {
                orgMap.putIfAbsent("DEPARTMENT:" + d, new OrgRef("DEPARTMENT", d));
            }
        }
//        for (DeveloperPlPersonInfo r : records) {
//            String p = r.getProductLine();
//            if (p != null && !p.isBlank()) {
//                orgMap.putIfAbsent("PRODUCT_LINE:" + p, new OrgRef("PRODUCT_LINE", p));
//            }
//        }
        orgMap.putIfAbsent("COMPANY:" + COMPANY_ORG_ID, new OrgRef("COMPANY", COMPANY_ORG_ID));
        return new ArrayList<>(orgMap.values());
    }

    /**
     * 获取全部组织列表(不按用户过滤)。发布目标可为任意已存在的组织,不限于用户自身归属:
     * 从 developer_pl_person_info 查询全部去重的 统计组/部门/产品线,并附加 COMPANY 维度。
     */
    public List<OrgRef> getAllOrgs() {
        Map<String, OrgRef> orgMap = new LinkedHashMap<>();
        collectOrgs(orgMap, "GROUP", personInfoMapper.selectAllStatisticsGroups());
        collectOrgs(orgMap, "DEPARTMENT", personInfoMapper.selectAllDepartments());
//        collectOrgs(orgMap, "PRODUCT_LINE", personInfoMapper.selectAllProductLines());
        orgMap.putIfAbsent("COMPANY:" + COMPANY_ORG_ID, new OrgRef("COMPANY", COMPANY_ORG_ID));
        return new ArrayList<>(orgMap.values());
    }

    /**
     * 获取组织审批人。从 skill_approver 表按 scope 查询,返回首个 ACTIVE 审批人(或签)。
     * COMPANY 类型固定查 approval_scope_name='杭研'。
     */
    public String getApprover(String orgType, String orgId) {
        String scopeName = "COMPANY".equals(orgType) ? COMPANY_APPROVAL_SCOPE_NAME : orgId;
        List<SkillApprover> approvers = skillApproverMapper.selectByScope(orgType, scopeName);
        if (approvers == null || approvers.isEmpty()) {
            log.warn("No active approver configured for {}:{} (scope={})", orgType, orgId, scopeName);
            return null;
        }
        return approvers.get(0).getUserId();
    }

    /**
     * 判断用户是否为审批人。使用短 TTL 缓存避免频繁查全表。
     */
    public boolean isApprover(String userId) {
        if (userId == null || userId.isBlank()) {
            return false;
        }
        Set<String> ids = getCachedApproverIds();
        return ids.contains(userId);
    }

    /** 获取组织显示名称。orgId 即名称,直接返回。 */
    public String getDisplayName(String orgType, String orgId) {
        return "COMPANY".equals(orgType) ? COMPANY_ORG_ID : orgId;
    }

    /** 获取维度类型前缀(如"小组"、"部门"、"产品线"、"杭研")。 */
    public String getTypeLabel(String orgType) {
        return ORG_TYPE_LABEL.getOrDefault(orgType, orgType);
    }

    /** 获取完整维度展示文本(如"小组:开发一组"、"杭研")。 */
    public String getFullDimensionLabel(String orgType, String orgId) {
        String name = getDisplayName(orgType, orgId);
        String label = getTypeLabel(orgType);
        if ("COMPANY".equals(orgType)) {
            return name;
        }
        return label + ":" + name;
    }

    /** 获取用户信息(含所属组织),供前端展示测试身份归属。 */
    public UserInfo getUserInfo(String userId) {
        List<OrgRef> orgRefs = getUserOrgs(userId);
        List<UserInfo.OrgInfo> orgs = orgRefs.stream()
                .map(ref -> new UserInfo.OrgInfo(ref.orgType(), ref.orgId(),
                        getDisplayName(ref.orgType(), ref.orgId())))
                .toList();
        return new UserInfo(userId, orgs);
    }

    /**
     * 批量解析 userId -> 姓名(取每个 user_id 首条非空姓名,ORDER BY id)。
     * 用于列表场景一次性填充 ownerName,避免逐条查询。
     * 无记录或姓名为空的 userId 不放入返回 Map,调用方按缺失回退到 userId 展示。
     */
    public Map<String, String> getUserNameMap(Collection<String> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Map.of();
        }
        List<String> distinct = userIds.stream()
                .filter(Objects::nonNull)
                .filter(id -> !id.isBlank())
                .distinct()
                .toList();
        if (distinct.isEmpty()) {
            return Map.of();
        }
        List<DeveloperPlPersonInfo> records = personInfoMapper.selectByUserIds(distinct);
        if (records == null || records.isEmpty()) {
            return Map.of();
        }
        Map<String, String> result = new HashMap<>();
        for (DeveloperPlPersonInfo r : records) {
            String uid = r.getUserId();
            String name = r.getName();
            if (uid == null || uid.isBlank() || name == null || name.isBlank()) {
                continue;
            }
            result.putIfAbsent(uid, name); // ORDER BY id,首条非空姓名
        }
        return result;
    }

    // ==================== 内部工具 ====================

    /** 将一组去重的组织 id 收集进 orgMap(跳过空串),键为 "orgType:orgId" 以保证去重。 */
    private void collectOrgs(Map<String, OrgRef> orgMap, String orgType, List<String> orgIds) {
        if (orgIds == null) {
            return;
        }
        for (String id : orgIds) {
            if (id != null && !id.isBlank()) {
                orgMap.putIfAbsent(orgType + ":" + id, new OrgRef(orgType, id));
            }
        }
    }

    private Set<String> getCachedApproverIds() {
        Set<String> cached = approverIdsCache;
        if (cached != null && (System.currentTimeMillis() - approverIdsCacheAt) < APPROVER_IDS_TTL_MS) {
            return cached;
        }
        List<String> ids = skillApproverMapper.selectAllApproverUserIds();
        Set<String> result = ids == null ? Set.of() : Set.copyOf(ids);
        approverIdsCache = result;
        approverIdsCacheAt = System.currentTimeMillis();
        return result;
    }
}
