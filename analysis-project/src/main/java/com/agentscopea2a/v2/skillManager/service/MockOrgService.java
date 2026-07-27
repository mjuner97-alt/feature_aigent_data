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
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Mock 组织服务:提供用户四维归属(GROUP/DEPARTMENT/PRODUCT_LINE/COMPANY)、
 * 组织显示名称、维度标签与审批人映射。数据硬编码,后续可迁移到数据库表。
 */
@Service
public class MockOrgService {

    /** 组织注册表:key = "ORG_TYPE:orgId",value = 显示名称。 */
    private static final Map<String, String> ORG_REGISTRY = Map.of(
            // GROUP 小组
            "GROUP:group_001",      "开发一组",
            "GROUP:group_002",      "开发二组",
            "GROUP:group_003",      "统计组",
            // DEPARTMENT 部门
            "DEPARTMENT:dept_001",  "研发部",
            "DEPARTMENT:dept_002",  "数据部",
            // PRODUCT_LINE 产品线
            "PRODUCT_LINE:pl_001",  "数据产品线",
            "PRODUCT_LINE:pl_002",  "办公产品线",
            // COMPANY 公司级(杭研)
            "COMPANY:hangyan",      "杭研"
    );

    /** 组织级别(用于展示前缀)。 */
    private static final Map<String, String> ORG_TYPE_LABEL = Map.of(
            "GROUP",        "小组",
            "DEPARTMENT",   "部门",
            "PRODUCT_LINE", "产品线",
            "COMPANY",      "杭研"
    );

    /** org -> approver 模拟映射。 */
    private static final Map<String, String> ORG_APPROVER = Map.of(
            "GROUP:group_001",          "approver_001",
            "GROUP:group_002",          "approver_001",
            "GROUP:group_003",          "approver_002",
            "DEPARTMENT:dept_001",      "approver_003",
            "DEPARTMENT:dept_002",      "approver_003",
            "PRODUCT_LINE:pl_001",      "approver_003",
            "PRODUCT_LINE:pl_002",      "approver_003",
            "COMPANY:hangyan",          "approver_003"
    );

    /** 审批人用户 id 集合。 */
    private static final Set<String> APPROVER_USER_IDS = Set.of("approver_001", "approver_002", "approver_003");

    /** 用户四维归属映射。 */
    private static final Map<String, List<OrgRef>> USER_ORGS = Map.of(
        "user_001",     List.of(
            new OrgRef("GROUP","group_001"),
            new OrgRef("DEPARTMENT","dept_001"),
            new OrgRef("PRODUCT_LINE","pl_001"),
            new OrgRef("COMPANY","hangyan")),
        "user_002",     List.of(
            new OrgRef("GROUP","group_001"),
            new OrgRef("DEPARTMENT","dept_001"),
            new OrgRef("PRODUCT_LINE","pl_001"),
            new OrgRef("COMPANY","hangyan")),
        "user_003",     List.of(
            new OrgRef("GROUP","group_003"),
            new OrgRef("DEPARTMENT","dept_002"),
            new OrgRef("PRODUCT_LINE","pl_002"),
            new OrgRef("COMPANY","hangyan")),
        "approver_001", List.of(
            new OrgRef("GROUP","group_001"),
            new OrgRef("DEPARTMENT","dept_001"),
            new OrgRef("PRODUCT_LINE","pl_001"),
            new OrgRef("COMPANY","hangyan")),
        "approver_002", List.of(
            new OrgRef("GROUP","group_003"),
            new OrgRef("DEPARTMENT","dept_002"),
            new OrgRef("PRODUCT_LINE","pl_002"),
            new OrgRef("COMPANY","hangyan")),
        "approver_003", List.of(
            new OrgRef("GROUP","group_001"),
            new OrgRef("DEPARTMENT","dept_001"),
            new OrgRef("PRODUCT_LINE","pl_001"),
            new OrgRef("COMPANY","hangyan")),
        "demo-user",    List.of(
            new OrgRef("GROUP","group_001"),
            new OrgRef("DEPARTMENT","dept_001"),
            new OrgRef("PRODUCT_LINE","pl_001"),
            new OrgRef("COMPANY","hangyan"))
    );

    /** 组织引用记录。 */
    public record OrgRef(String orgType, String orgId) {}

    /** 获取用户所属组织列表。 */
    public List<OrgRef> getUserOrgs(String userId) {
        return USER_ORGS.getOrDefault(userId, List.of());
    }

    /** 获取组织审批人。 */
    public String getApprover(String orgType, String orgId) {
        return ORG_APPROVER.get(orgType + ":" + orgId);
    }

    /** 判断用户是否为审批人。 */
    public boolean isApprover(String userId) {
        return APPROVER_USER_IDS.contains(userId);
    }

    /** 获取组织显示名称(如"开发一组"、"杭研")。 */
    public String getDisplayName(String orgType, String orgId) {
        return ORG_REGISTRY.getOrDefault(orgType + ":" + orgId, orgId);
    }

    /** 获取维度类型前缀(如"小组"、"部门"、"产品线"、"杭研")。 */
    public String getTypeLabel(String orgType) {
        return ORG_TYPE_LABEL.getOrDefault(orgType, orgType);
    }

    /** 获取完整维度展示文本(如"小组:开发一组"、"杭研")。 */
    public String getFullDimensionLabel(String orgType, String orgId) {
        String name = getDisplayName(orgType, orgId);
        String label = getTypeLabel(orgType);
        // COMPANY 类型直接展示"杭研",不加前缀
        if ("COMPANY".equals(orgType)) {
            return name;
        }
        return label + ":" + name;
    }

    /** 获取用户信息(含所属组织),供前端展示测试身份归属。 */
    public UserInfo getUserInfo(String userId) {
        List<OrgRef> orgRefs = USER_ORGS.getOrDefault(userId, List.of());
        List<UserInfo.OrgInfo> orgs = orgRefs.stream()
                .map(ref -> new UserInfo.OrgInfo(ref.orgType(), ref.orgId(),
                        getDisplayName(ref.orgType(), ref.orgId())))
                .toList();
        return new UserInfo(userId, orgs);
    }
}
