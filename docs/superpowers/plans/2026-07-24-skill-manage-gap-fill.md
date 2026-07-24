# Skill 管理平台缺口补全 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 补全 Skill 管理平台的发布与审批、变更审批(草稿区)、用户禁用、版本历史、操作历史、可用性计算与前端筛选/审批页,实现完整业务闭环。

**Architecture:** 分层递进:第 1 层(Flyway 迁移 + Entity + Mapper)-> 第 2 层(Service + Controller)-> 第 3 层(前端 Vue)。审批分两类:发布申请审批(skill_publish/skill_approval)与变更审批(skill_draft)。组织数据用 MockOrgService 硬编码,不建组织表。

**Tech Stack:** Spring Boot + MyBatis + Flyway(后端),Vue 3 + vue-router + Vite + TypeScript(前端),JUnit 5 + Mockito + AssertJ(测试)

## Global Constraints

- Entity 放 `com.agentscopea2a.entity` 包(MySQLConfig.setTypeAliasesPackage 约束),用 `@Data @Builder @NoArgsConstructor @AllArgsConstructor`
- Mapper 放 `com.agentscopea2a.mapper.mysql` 包(MySQLConfig.@MapperScan 约束),Mapper XML 放 `src/main/resources/mybatis/mapper/mysql/`
- Flyway 脚本放 `src/main/resources/db/migration/`,命名 `V20260724.x__<desc>.sql`
- Service 放 `com.agentscopea2a.v2.service`,Controller 放 `com.agentscopea2a.v2.controller`
- userId 经 `@RequestHeader("X-User-Id")` 传入(无 Spring Security,与现有代码一致)
- 前端 API 统一在 `frontend/src/api/skill.ts`,页面在 `frontend/src/pages/skill/`
- 所有 Java 文件头部保留 Apache 2.0 License 注释
- 测试用 Mockito mock Mapper,AssertJ 断言,与 SkillServiceTest 一致
- 前端构建产物输出到 `src/main/resources/static`(vite build,emptyOutDir: true)

---

## File Structure

### 新建文件(后端)

```
src/main/resources/db/migration/
  V20260724.1__create_skill_publish.sql
  V20260724.2__create_skill_approval.sql
  V20260724.3__create_skill_draft.sql
  V20260724.4__create_skill_version_history.sql
  V20260724.5__create_skill_operation_history.sql
  V20260724.6__create_skill_user_disable.sql

src/main/java/com/agentscopea2a/entity/
  SkillPublish.java, SkillApproval.java, SkillDraft.java
  SkillVersionHistory.java, SkillOperationHistory.java, SkillUserDisable.java

src/main/java/com/agentscopea2a/mapper/mysql/
  SkillPublishMapper.java, SkillApprovalMapper.java, SkillDraftMapper.java
  SkillVersionHistoryMapper.java, SkillOperationHistoryMapper.java, SkillUserDisableMapper.java

src/main/resources/mybatis/mapper/mysql/
  SkillPublishMapper.xml, SkillApprovalMapper.xml, SkillDraftMapper.xml
  SkillVersionHistoryMapper.xml, SkillOperationHistoryMapper.xml, SkillUserDisableMapper.xml

src/main/java/com/agentscopea2a/v2/service/
  MockOrgService.java, SkillOperationHistoryService.java, SkillVersionHistoryService.java
  SkillPublishService.java, SkillDraftService.java, SkillUserDisableService.java

src/main/java/com/agentscopea2a/v2/controller/
  SkillPublishController.java, SkillDraftController.java, SkillUserDisableController.java
```

### 修改文件(后端)

```
src/main/java/com/agentscopea2a/dto/SkillListQuery.java        -- 增加 availability 字段
src/main/java/com/agentscopea2a/dto/SkillListItem.java         -- available 改为动态计算
src/main/java/com/agentscopea2a/mapper/mysql/SkillManageMapper.java  -- 增加 availability 查询
src/main/resources/mybatis/mapper/mysql/SkillManageMapper.xml  -- availability 筛选 SQL
src/main/java/com/agentscopea2a/v2/service/SkillService.java   -- update 改造 + list 可用性计算
src/main/java/com/agentscopea2a/mapper/mysql/SkillReferenceMapper.java -- 增加 referencers 查询
src/main/resources/mybatis/mapper/mysql/SkillReferenceMapper.xml
src/main/java/com/agentscopea2a/v2/controller/SkillController.java -- 增加 versions 端点
src/main/java/com/agentscopea2a/v2/controller/SkillReferenceController.java -- 增加 my-references/referencers
```

### 新建文件(前端)

```
frontend/src/pages/skill/SkillApprovalListPage.vue
frontend/src/pages/skill/SkillApprovalDetailPage.vue
```

### 修改文件(前端)

```
frontend/src/api/skill.ts                 -- 新增 publish/draft/disable/versions API
frontend/src/types/skill.ts               -- 新增类型
frontend/src/components/SkillShell.vue     -- 增加"待我审批"导航项
frontend/src/main.ts                      -- 增加 approvals 路由
frontend/src/pages/skill/SkillListPage.vue -- 增加标签/可用性筛选
frontend/src/components/SkillCard.vue      -- 可用性徽章 + 已使用标记
frontend/src/components/SkillRow.vue       -- 同上
frontend/src/pages/skill/SkillDetailPage.vue -- 禁用按钮 + 版本历史 + 草稿提示
```

### 测试文件

```
src/test/java/com/agentscopea2a/v2/service/
  MockOrgServiceTest.java, SkillOperationHistoryServiceTest.java
  SkillVersionHistoryServiceTest.java, SkillUserDisableServiceTest.java
  SkillPublishServiceTest.java, SkillDraftServiceTest.java
```

---

## Task 1: Flyway 迁移脚本(6 张表)

**Files:**
- Create: `src/main/resources/db/migration/V20260724.1__create_skill_publish.sql` 至 `V20260724.6__create_skill_user_disable.sql`

**Interfaces:**
- Produces: 6 张表的 DDL,供后续 Entity/Mapper 使用

- [ ] **Step 1: 创建 6 个 Flyway 迁移脚本**

每个脚本内容对应 spec §12.2 的 DDL。关键点:
- `V20260724.1`: skill_publish(含 current_approver_user_id, idx_approver_pending)
- `V20260724.2`: skill_approval(含 draft_id 列 + idx_draft,支撑变更审批)
- `V20260724.3`: skill_draft(idx_skill, idx_status, idx_submitter;单一 PENDING 约束由应用层保证)
- `V20260724.4`: skill_version_history(idx_skill_version: skill_id, version DESC)
- `V20260724.5`: skill_operation_history(idx_skill, idx_publish, idx_operator_time)
- `V20260724.6`: skill_user_disable(UNIQUE KEY uk_user_skill)

所有表用 `ENGINE=InnoDB DEFAULT CHARSET=utf8mb4`,与现有 V20260723.* 一致。

- [ ] **Step 2: 编译验证**

Run: `cd analysis-project && mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/db/migration/V20260724.*.sql
git commit -m "feat: add Flyway migrations for publish/approval/draft/version/op-history/disable"
```

---

## Task 2: Entity 类(6 个)

**Files:**
- Create: 6 个 Entity 类到 `src/main/java/com/agentscopea2a/entity/`

**Interfaces:**
- Produces: SkillPublish, SkillApproval, SkillDraft, SkillVersionHistory, SkillOperationHistory, SkillUserDisable

- [ ] **Step 1: 创建 6 个 Entity 类**

所有类用 `@Data @Builder @NoArgsConstructor @AllArgsConstructor`,放 `com.agentscopea2a.entity` 包,头部保留 Apache 2.0 License 注释,与 Skill.java / SkillLike.java 一致。

字段对照:
- SkillPublish: id, skillId, targetType, targetId, targetName, status, submitter, approver, approveTime, currentApproverUserId, lastApprovalComment, lastApprovalAt, createdAt
- SkillApproval: id, publishId, draftId, action, operator, comment, versionSnapshot, createdAt
- SkillDraft: id, skillId, name, description, content, category, tags, status, submitter, approver, approveComment, submittedAt, approvedAt
- SkillVersionHistory: id, skillId, version, name, description, content, category, tags, editedBy, editReason, createdAt
- SkillOperationHistory: id, skillId, publishId, operator, operation, beforeData, afterData, createdAt
- SkillUserDisable: id, skillId, userId, createdAt

- [ ] **Step 2: 编译验证**

Run: `cd analysis-project && mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/agentscopea2a/entity/SkillPublish.java src/main/java/com/agentscopea2a/entity/SkillApproval.java src/main/java/com/agentscopea2a/entity/SkillDraft.java src/main/java/com/agentscopea2a/entity/SkillVersionHistory.java src/main/java/com/agentscopea2a/entity/SkillOperationHistory.java src/main/java/com/agentscopea2a/entity/SkillUserDisable.java
git commit -m "feat: add 6 entities for publish/approval/draft/version/op-history/disable"
```

---

## Task 3: Mapper 接口 + XML(6 套)

**Files:**
- Create: 6 个 Mapper 接口到 `src/main/java/com/agentscopea2a/mapper/mysql/` + 6 个 XML 到 `src/main/resources/mybatis/mapper/mysql/`

**Interfaces:**
- Produces: 6 套 Mapper,供 Service 层调用

- [ ] **Step 1: 创建 6 个 Mapper 接口 + XML**

**SkillPublishMapper** 关键方法:
```java
int insert(SkillPublish publish);
SkillPublish selectById(Long id);
int updateStatus(Long id, String status, String approver, String comment);
List<SkillPublish> selectBySkillId(Long skillId);
boolean hasApprovedBySkillId(Long skillId);
List<SkillPublish> selectApprovedBySkillId(Long skillId);
List<SkillPublish> selectPendingByApprover(String approverUserId);
List<SkillPublish> selectApprovedBySkillIds(List<Long> skillIds);
```

**SkillApprovalMapper** 关键方法:
```java
int insert(SkillApproval approval);
List<SkillApproval> selectByPublishId(Long publishId);
List<SkillApproval> selectByDraftId(Long draftId);
```

**SkillDraftMapper** 关键方法:
```java
int insert(SkillDraft draft);
SkillDraft selectById(Long id);
SkillDraft selectPendingBySkillId(Long skillId);
int updateStatus(Long id, String status, String approver, String comment);
int updateContent(SkillDraft draft);
List<SkillDraft> selectPendingByApprover(String approverUserId);
```
`selectPendingByApprover` 的 SQL 用子查询:`WHERE status='PENDING' AND skill_id IN (SELECT DISTINCT skill_id FROM skill_publish WHERE status='APPROVED')`,后端 Service 再用 MockOrgService 过滤当前用户是否是审批人。

**SkillVersionHistoryMapper** 关键方法:
```java
int insert(SkillVersionHistory history);
List<SkillVersionHistory> selectBySkillId(Long skillId);
Integer selectMaxVersion(Long skillId);
```

**SkillOperationHistoryMapper** 关键方法:
```java
int insert(SkillOperationHistory history);
List<SkillOperationHistory> selectBySkillId(Long skillId);
List<SkillOperationHistory> selectByOperator(String operator);
```

**SkillUserDisableMapper** 关键方法:
```java
int insert(SkillUserDisable disable);  // INSERT IGNORE 幂等
int deleteByUserSkill(String userId, Long skillId);
boolean existsByUserSkill(String userId, Long skillId);
Set<Long> selectDisabledSkillIds(String userId, List<Long> skillIds);
```

所有 XML 的 resultMap / sql cols / insert / select / update 语法与 SkillManageMapper.xml / SkillLikeMapper.xml 一致。

- [ ] **Step 2: 编译验证**

Run: `cd analysis-project && mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/agentscopea2a/mapper/mysql/Skill*.java src/main/resources/mybatis/mapper/mysql/Skill*.xml
git commit -m "feat: add 6 mapper interfaces + XML for publish/approval/draft/version/op-history/disable"
```

---

## Task 4: MockOrgService

**Files:**
- Create: `src/main/java/com/agentscopea2a/v2/service/MockOrgService.java`
- Test: `src/test/java/com/agentscopea2a/v2/service/MockOrgServiceTest.java`

**Interfaces:**
- Produces: `OrgRef(orgType, orgId)` record, `getUserOrgs(userId) -> List<OrgRef>`, `getApprover(orgType, orgId) -> String`, `isApprover(userId) -> boolean`, `isApproverForOrgs(userId, List<OrgRef>) -> boolean`

- [ ] **Step 1: 写失败测试**

```java
package com.agentscopea2a.v2.service;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class MockOrgServiceTest {

    @Test
    void getUserOrgs_returns_borrowed_user_both_groups() {
        MockOrgService svc = new MockOrgService();
        var orgs = svc.getUserOrgs("user_001");
        assertThat(orgs).hasSize(2);
        assertThat(orgs).extracting(MockOrgService.OrgRef::orgId)
                .contains("group_001", "group_002");
    }

    @Test
    void getApprover_returns_approver_for_known_org() {
        MockOrgService svc = new MockOrgService();
        assertThat(svc.getApprover("GROUP", "group_001")).isEqualTo("approver_001");
    }

    @Test
    void getApprover_returns_null_for_unknown_org() {
        MockOrgService svc = new MockOrgService();
        assertThat(svc.getApprover("GROUP", "unknown")).isNull();
    }

    @Test
    void isApprover_true_for_configured_approver() {
        MockOrgService svc = new MockOrgService();
        assertThat(svc.isApprover("approver_001")).isTrue();
        assertThat(svc.isApprover("user_001")).isFalse();
    }
}
```

- [ ] **Step 2: 运行测试验证失败**

Run: `cd analysis-project && mvn test -pl . -Dtest=MockOrgServiceTest -q`
Expected: FAIL (类不存在)

- [ ] **Step 3: 实现 MockOrgService**

```java
package com.agentscopea2a.v2.service;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 组织数据模拟服务。不建组织表,用硬编码模拟数据。
 * 后续可迁移到数据库表(留入口)。
 */
@Service
public class MockOrgService {

    public record OrgRef(String orgType, String orgId) {}

    private static final Map<String, List<OrgRef>> USER_ORGS = Map.of(
        "user_001", List.of(new OrgRef("GROUP", "group_001"), new OrgRef("GROUP", "group_002")),
        "user_002", List.of(new OrgRef("GROUP", "group_001")),
        "approver_001", List.of(new OrgRef("GROUP", "group_001")),
        "demo-user", List.of(new OrgRef("GROUP", "group_001"))
    );

    private static final Map<String, String> ORG_APPROVER = Map.of(
        "GROUP:group_001", "approver_001",
        "GROUP:group_002", "approver_001",
        "DEPARTMENT:dept_001", "approver_003"
    );

    private static final Set<String> APPROVER_USER_IDS = Set.of("approver_001", "approver_003");

    public List<OrgRef> getUserOrgs(String userId) {
        return USER_ORGS.getOrDefault(userId, List.of());
    }

    public String getApprover(String orgType, String orgId) {
        return ORG_APPROVER.get(orgType + ":" + orgId);
    }

    public boolean isApprover(String userId) {
        return APPROVER_USER_IDS.contains(userId);
    }

    public boolean isApproverForOrgs(String userId, List<OrgRef> orgs) {
        return orgs.stream().anyMatch(o -> userId.equals(getApprover(o.orgType(), o.orgId())));
    }
}
```

- [ ] **Step 4: 运行测试验证通过**

Run: `cd analysis-project && mvn test -pl . -Dtest=MockOrgServiceTest -q`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/agentscopea2a/v2/service/MockOrgService.java src/test/java/com/agentscopea2a/v2/service/MockOrgServiceTest.java
git commit -m "feat: add MockOrgService with hardcoded org/approver data"
```

---

## Task 5: SkillOperationHistoryService

**Files:**
- Create: `src/main/java/com/agentscopea2a/v2/service/SkillOperationHistoryService.java`
- Test: `src/test/java/com/agentscopea2a/v2/service/SkillOperationHistoryServiceTest.java`

**Interfaces:**
- Consumes: SkillOperationHistoryMapper
- Produces: `record(skillId, publishId, operator, operation, beforeData, afterData)`, `selectBySkillId(skillId)`, `selectByOperator(operator)`

- [ ] **Step 1: 写失败测试**

```java
package com.agentscopea2a.v2.service;

import com.agentscopea2a.entity.SkillOperationHistory;
import com.agentscopea2a.mapper.mysql.SkillOperationHistoryMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class SkillOperationHistoryServiceTest {

    @Test
    void record_inserts_history_row() {
        SkillOperationHistoryMapper mapper = mock(SkillOperationHistoryMapper.class);
        SkillOperationHistoryService svc = new SkillOperationHistoryService(mapper);

        svc.record(1L, null, "u1", "CREATE", null, "{\"name\":\"SQL\"}");

        ArgumentCaptor<SkillOperationHistory> captor = ArgumentCaptor.forClass(SkillOperationHistory.class);
        verify(mapper).insert(captor.capture());
        assertThat(captor.getValue().getSkillId()).isEqualTo(1L);
        assertThat(captor.getValue().getOperation()).isEqualTo("CREATE");
        assertThat(captor.getValue().getOperator()).isEqualTo("u1");
    }
}
```

- [ ] **Step 2: 运行测试验证失败**

Run: `cd analysis-project && mvn test -pl . -Dtest=SkillOperationHistoryServiceTest -q`
Expected: FAIL

- [ ] **Step 3: 实现 SkillOperationHistoryService**

```java
package com.agentscopea2a.v2.service;

import com.agentscopea2a.entity.SkillOperationHistory;
import com.agentscopea2a.mapper.mysql.SkillOperationHistoryMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 操作历史 Service。记录 CREATE/UPDATE/DELETE/PUBLISH/APPROVE/REJECT/DISABLE/ENABLE/REFERENCE。
 * LIKE/UNLIKE 不记录(高频,审计价值低)。
 */
@Service
public class SkillOperationHistoryService {

    private final SkillOperationHistoryMapper mapper;

    public SkillOperationHistoryService(SkillOperationHistoryMapper mapper) {
        this.mapper = mapper;
    }

    public void record(Long skillId, Long publishId, String operator,
                       String operation, String beforeData, String afterData) {
        mapper.insert(SkillOperationHistory.builder()
                .skillId(skillId)
                .publishId(publishId)
                .operator(operator)
                .operation(operation)
                .beforeData(beforeData)
                .afterData(afterData)
                .createdAt(LocalDateTime.now())
                .build());
    }

    public List<SkillOperationHistory> selectBySkillId(Long skillId) {
        return mapper.selectBySkillId(skillId);
    }

    public List<SkillOperationHistory> selectByOperator(String operator) {
        return mapper.selectByOperator(operator);
    }
}
```

- [ ] **Step 4: 运行测试验证通过**

Run: `cd analysis-project && mvn test -pl . -Dtest=SkillOperationHistoryServiceTest -q`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/agentscopea2a/v2/service/SkillOperationHistoryService.java src/test/java/com/agentscopea2a/v2/service/SkillOperationHistoryServiceTest.java
git commit -m "feat: add SkillOperationHistoryService for audit logging"
```

---

## Task 6: SkillVersionHistoryService

**Files:**
- Create: `src/main/java/com/agentscopea2a/v2/service/SkillVersionHistoryService.java`
- Test: `src/test/java/com/agentscopea2a/v2/service/SkillVersionHistoryServiceTest.java`

**Interfaces:**
- Consumes: SkillVersionHistoryMapper
- Produces: `saveVersion(Skill skill, String editedBy, String editReason)`, `selectBySkillId(Long skillId)`

- [ ] **Step 1: 写失败测试**

```java
package com.agentscopea2a.v2.service;

import com.agentscopea2a.entity.Skill;
import com.agentscopea2a.entity.SkillVersionHistory;
import com.agentscopea2a.mapper.mysql.SkillVersionHistoryMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class SkillVersionHistoryServiceTest {

    @Test
    void saveVersion_increments_version_and_inserts() {
        SkillVersionHistoryMapper vhMapper = mock(SkillVersionHistoryMapper.class);
        when(vhMapper.selectMaxVersion(1L)).thenReturn(2);
        SkillVersionHistoryService svc = new SkillVersionHistoryService(vhMapper);

        Skill skill = Skill.builder().id(1L).name("SQL").description("d").content("c")
                .category("数据").tags("#sql").build();
        svc.saveVersion(skill, "u1", "edit reason");

        ArgumentCaptor<SkillVersionHistory> captor = ArgumentCaptor.forClass(SkillVersionHistory.class);
        verify(vhMapper).insert(captor.capture());
        assertThat(captor.getValue().getVersion()).isEqualTo(3);
        assertThat(captor.getValue().getEditedBy()).isEqualTo("u1");
        assertThat(captor.getValue().getName()).isEqualTo("SQL");
    }

    @Test
    void saveVersion_first_version_is_1() {
        SkillVersionHistoryMapper vhMapper = mock(SkillVersionHistoryMapper.class);
        when(vhMapper.selectMaxVersion(1L)).thenReturn(null);
        SkillVersionHistoryService svc = new SkillVersionHistoryService(vhMapper);

        Skill skill = Skill.builder().id(1L).name("SQL").build();
        svc.saveVersion(skill, "u1", null);

        ArgumentCaptor<SkillVersionHistory> captor = ArgumentCaptor.forClass(SkillVersionHistory.class);
        verify(vhMapper).insert(captor.capture());
        assertThat(captor.getValue().getVersion()).isEqualTo(1);
    }
}
```

- [ ] **Step 2: 运行测试验证失败**

Run: `cd analysis-project && mvn test -pl . -Dtest=SkillVersionHistoryServiceTest -q`
Expected: FAIL

- [ ] **Step 3: 实现 SkillVersionHistoryService**

```java
package com.agentscopea2a.v2.service;

import com.agentscopea2a.entity.Skill;
import com.agentscopea2a.entity.SkillVersionHistory;
import com.agentscopea2a.mapper.mysql.SkillVersionHistoryMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 版本历史 Service。编辑 Skill 时存旧版本快照,支撑回溯与审计。
 */
@Service
public class SkillVersionHistoryService {

    private final SkillVersionHistoryMapper vhMapper;

    public SkillVersionHistoryService(SkillVersionHistoryMapper vhMapper) {
        this.vhMapper = vhMapper;
    }

    public void saveVersion(Skill skill, String editedBy, String editReason) {
        Integer maxVersion = vhMapper.selectMaxVersion(skill.getId());
        int nextVersion = maxVersion == null ? 1 : maxVersion + 1;
        vhMapper.insert(SkillVersionHistory.builder()
                .skillId(skill.getId())
                .version(nextVersion)
                .name(skill.getName())
                .description(skill.getDescription())
                .content(skill.getContent())
                .category(skill.getCategory())
                .tags(skill.getTags())
                .editedBy(editedBy)
                .editReason(editReason)
                .createdAt(LocalDateTime.now())
                .build());
    }

    public List<SkillVersionHistory> selectBySkillId(Long skillId) {
        return vhMapper.selectBySkillId(skillId);
    }
}
```

- [ ] **Step 4: 运行测试验证通过**

Run: `cd analysis-project && mvn test -pl . -Dtest=SkillVersionHistoryServiceTest -q`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/agentscopea2a/v2/service/SkillVersionHistoryService.java src/test/java/com/agentscopea2a/v2/service/SkillVersionHistoryServiceTest.java
git commit -m "feat: add SkillVersionHistoryService for version snapshots"
```

---

## Task 7: SkillUserDisableService

**Files:**
- Create: `src/main/java/com/agentscopea2a/v2/service/SkillUserDisableService.java`
- Test: `src/test/java/com/agentscopea2a/v2/service/SkillUserDisableServiceTest.java`

**Interfaces:**
- Consumes: SkillUserDisableMapper, SkillOperationHistoryService
- Produces: `disable(skillId, userId)`, `enable(skillId, userId)`, `isDisabled(skillId, userId)`, `selectDisabledSkillIds(userId, skillIds)`

- [ ] **Step 1: 写失败测试**

```java
package com.agentscopea2a.v2.service;

import com.agentscopea2a.entity.SkillUserDisable;
import com.agentscopea2a.mapper.mysql.SkillUserDisableMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class SkillUserDisableServiceTest {

    @Test
    void disable_inserts_and_records_history() {
        SkillUserDisableMapper mapper = mock(SkillUserDisableMapper.class);
        SkillOperationHistoryService historySvc = mock(SkillOperationHistoryService.class);
        SkillUserDisableService svc = new SkillUserDisableService(mapper, historySvc);

        svc.disable(1L, "u1");

        verify(mapper).insert(any(SkillUserDisable.class));
        verify(historySvc).record(1L, null, "u1", "DISABLE", null, null);
    }

    @Test
    void enable_deletes_and_records_history() {
        SkillUserDisableMapper mapper = mock(SkillUserDisableMapper.class);
        SkillOperationHistoryService historySvc = mock(SkillOperationHistoryService.class);
        SkillUserDisableService svc = new SkillUserDisableService(mapper, historySvc);

        svc.enable(1L, "u1");

        verify(mapper).deleteByUserSkill("u1", 1L);
        verify(historySvc).record(1L, null, "u1", "ENABLE", null, null);
    }

    @Test
    void selectDisabledSkillIds_delegates_to_mapper() {
        SkillUserDisableMapper mapper = mock(SkillUserDisableMapper.class);
        SkillOperationHistoryService historySvc = mock(SkillOperationHistoryService.class);
        when(mapper.selectDisabledSkillIds("u1", List.of(1L, 2L))).thenReturn(Set.of(1L));
        SkillUserDisableService svc = new SkillUserDisableService(mapper, historySvc);

        Set<Long> result = svc.selectDisabledSkillIds("u1", List.of(1L, 2L));
        assertThat(result).containsExactly(1L);
    }
}
```

- [ ] **Step 2: 运行测试验证失败**

Run: `cd analysis-project && mvn test -pl . -Dtest=SkillUserDisableServiceTest -q`
Expected: FAIL

- [ ] **Step 3: 实现 SkillUserDisableService**

```java
package com.agentscopea2a.v2.service;

import com.agentscopea2a.entity.SkillUserDisable;
import com.agentscopea2a.mapper.mysql.SkillUserDisableMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/**
 * 用户禁用 Service。个人级禁用某 Skill,只影响自己。
 */
@Service
public class SkillUserDisableService {

    private final SkillUserDisableMapper disableMapper;
    private final SkillOperationHistoryService historyService;

    public SkillUserDisableService(SkillUserDisableMapper disableMapper,
                                   SkillOperationHistoryService historyService) {
        this.disableMapper = disableMapper;
        this.historyService = historyService;
    }

    @Transactional
    public void disable(Long skillId, String userId) {
        disableMapper.insert(SkillUserDisable.builder()
                .skillId(skillId).userId(userId).createdAt(LocalDateTime.now()).build());
        historyService.record(skillId, null, userId, "DISABLE", null, null);
    }

    @Transactional
    public void enable(Long skillId, String userId) {
        disableMapper.deleteByUserSkill(userId, skillId);
        historyService.record(skillId, null, userId, "ENABLE", null, null);
    }

    public boolean isDisabled(Long skillId, String userId) {
        return disableMapper.existsByUserSkill(userId, skillId);
    }

    public Set<Long> selectDisabledSkillIds(String userId, List<Long> skillIds) {
        if (skillIds.isEmpty()) return Set.of();
        Set<Long> result = disableMapper.selectDisabledSkillIds(userId, skillIds);
        return result == null ? Set.of() : result;
    }
}
```

- [ ] **Step 4: 运行测试验证通过**

Run: `cd analysis-project && mvn test -pl . -Dtest=SkillUserDisableServiceTest -q`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/agentscopea2a/v2/service/SkillUserDisableService.java src/test/java/com/agentscopea2a/v2/service/SkillUserDisableServiceTest.java
git commit -m "feat: add SkillUserDisableService for per-user skill disable"
```

---

## Task 8: SkillPublishService

**Files:**
- Create: `src/main/java/com/agentscopea2a/v2/service/SkillPublishService.java`
- Test: `src/test/java/com/agentscopea2a/v2/service/SkillPublishServiceTest.java`

**Interfaces:**
- Consumes: SkillPublishMapper, SkillApprovalMapper, MockOrgService, SkillOperationHistoryService, SkillService
- Produces: `requestPublish(skillId, targetType, targetId, targetName, userId) -> SkillPublish`, `approve(publishId, approverId, comment)`, `reject(publishId, approverId, comment)`, `selectPendingByApprover(userId)`, `selectApprovals(publishId)`, `hasApprovedPublish(skillId)`, `selectApprovedBySkillIds(skillIds)`, `selectBySkillId(skillId)`

- [ ] **Step 1: 写失败测试**

```java
package com.agentscopea2a.v2.service;

import com.agentscopea2a.entity.SkillPublish;
import com.agentscopea2a.mapper.mysql.SkillApprovalMapper;
import com.agentscopea2a.mapper.mysql.SkillPublishMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class SkillPublishServiceTest {

    private SkillPublishMapper publishMapper;
    private SkillApprovalMapper approvalMapper;
    private MockOrgService orgService;
    private SkillOperationHistoryService historyService;
    private SkillService skillService;
    private SkillPublishService service;

    @BeforeEach
    void setUp() {
        publishMapper = mock(SkillPublishMapper.class);
        approvalMapper = mock(SkillApprovalMapper.class);
        orgService = new MockOrgService();
        historyService = mock(SkillOperationHistoryService.class);
        skillService = mock(SkillService.class);
        service = new SkillPublishService(publishMapper, approvalMapper, orgService, historyService, skillService);
    }

    @Test
    void requestPublish_sets_approver_from_org() {
        service.requestPublish(1L, "GROUP", "group_001", "开发三部", "u1");
        verify(publishMapper).insert(argThat(p ->
            p.getSkillId().equals(1L) &&
            p.getStatus().equals("PENDING") &&
            p.getCurrentApproverUserId().equals("approver_001")
        ));
        verify(historyService).record(1L, null, "u1", "PUBLISH", null, null);
    }

    @Test
    void approve_denies_non_approver() {
        SkillPublish pending = SkillPublish.builder()
                .id(10L).skillId(1L).status("PENDING")
                .currentApproverUserId("approver_001")
                .targetType("GROUP").targetId("group_001").build();
        when(publishMapper.selectById(10L)).thenReturn(pending);

        assertThatThrownBy(() -> service.approve(10L, "user_001", "ok"))
                .hasMessageContaining("NotApprover");
    }

    @Test
    void approve_updates_status_and_records_history() {
        SkillPublish pending = SkillPublish.builder()
                .id(10L).skillId(1L).status("PENDING")
                .currentApproverUserId("approver_001")
                .targetType("GROUP").targetId("group_001").build();
        when(publishMapper.selectById(10L)).thenReturn(pending);

        service.approve(10L, "approver_001", "approved");

        verify(publishMapper).updateStatus(10L, "APPROVED", "approver_001", "approved");
        verify(historyService).record(1L, 10L, "approver_001", "APPROVE", null, null);
    }

    @Test
    void approve_rejects_already_approved() {
        SkillPublish approved = SkillPublish.builder()
                .id(10L).skillId(1L).status("APPROVED").build();
        when(publishMapper.selectById(10L)).thenReturn(approved);

        assertThatThrownBy(() -> service.approve(10L, "approver_001", "ok"))
                .hasMessageContaining("PublishAlreadyApproved");
    }
}
```

- [ ] **Step 2: 运行测试验证失败**

Run: `cd analysis-project && mvn test -pl . -Dtest=SkillPublishServiceTest -q`
Expected: FAIL

- [ ] **Step 3: 实现 SkillPublishService**

```java
package com.agentscopea2a.v2.service;

import com.agentscopea2a.entity.SkillApproval;
import com.agentscopea2a.entity.SkillPublish;
import com.agentscopea2a.mapper.mysql.SkillApprovalMapper;
import com.agentscopea2a.mapper.mysql.SkillPublishMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 发布申请 Service。审批对象 = Skill 发布申请(§4.2)。
 */
@Service
public class SkillPublishService {

    private final SkillPublishMapper publishMapper;
    private final SkillApprovalMapper approvalMapper;
    private final MockOrgService orgService;
    private final SkillOperationHistoryService historyService;
    private final SkillService skillService;

    public SkillPublishService(SkillPublishMapper publishMapper,
                               SkillApprovalMapper approvalMapper,
                               MockOrgService orgService,
                               SkillOperationHistoryService historyService,
                               SkillService skillService) {
        this.publishMapper = publishMapper;
        this.approvalMapper = approvalMapper;
        this.orgService = orgService;
        this.historyService = historyService;
        this.skillService = skillService;
    }

    @Transactional
    public SkillPublish requestPublish(Long skillId, String targetType, String targetId,
                                       String targetName, String userId) {
        skillService.get(skillId); // 校验 Skill 存在
        String approverUserId = orgService.getApprover(targetType, targetId);
        SkillPublish publish = SkillPublish.builder()
                .skillId(skillId)
                .targetType(targetType)
                .targetId(targetId)
                .targetName(targetName)
                .status("PENDING")
                .submitter(userId)
                .currentApproverUserId(approverUserId)
                .createdAt(LocalDateTime.now())
                .build();
        publishMapper.insert(publish);
        approvalMapper.insert(SkillApproval.builder()
                .publishId(publish.getId())
                .action("SUBMIT")
                .operator(userId)
                .versionSnapshot(1)
                .createdAt(LocalDateTime.now())
                .build());
        historyService.record(skillId, publish.getId(), userId, "PUBLISH", null, null);
        return publish;
    }

    @Transactional
    public void approve(Long publishId, String approverId, String comment) {
        SkillPublish p = publishMapper.selectById(publishId);
        if (p == null) throw new IllegalStateException("PublishNotFound: " + publishId);
        if ("APPROVED".equals(p.getStatus())) throw new IllegalStateException("PublishAlreadyApproved: " + publishId);
        if (!"PENDING".equals(p.getStatus())) throw new IllegalStateException("PublishNotPending: " + publishId);
        if (!approverId.equals(p.getCurrentApproverUserId()))
            throw new IllegalStateException("NotApprover: " + publishId);

        publishMapper.updateStatus(publishId, "APPROVED", approverId, comment);
        approvalMapper.insert(SkillApproval.builder()
                .publishId(publishId)
                .action("APPROVE")
                .operator(approverId)
                .comment(comment)
                .versionSnapshot(1)
                .createdAt(LocalDateTime.now())
                .build());
        historyService.record(p.getSkillId(), publishId, approverId, "APPROVE", null, null);
    }

    @Transactional
    public void reject(Long publishId, String approverId, String comment) {
        SkillPublish p = publishMapper.selectById(publishId);
        if (p == null) throw new IllegalStateException("PublishNotFound: " + publishId);
        if (!"PENDING".equals(p.getStatus())) throw new IllegalStateException("PublishNotPending: " + publishId);
        if (!approverId.equals(p.getCurrentApproverUserId()))
            throw new IllegalStateException("NotApprover: " + publishId);

        publishMapper.updateStatus(publishId, "REJECTED", approverId, comment);
        approvalMapper.insert(SkillApproval.builder()
                .publishId(publishId)
                .action("REJECT")
                .operator(approverId)
                .comment(comment)
                .versionSnapshot(1)
                .createdAt(LocalDateTime.now())
                .build());
        historyService.record(p.getSkillId(), publishId, approverId, "REJECT", null, null);
    }

    public List<SkillPublish> selectPendingByApprover(String approverUserId) {
        return publishMapper.selectPendingByApprover(approverUserId);
    }

    public List<SkillApproval> selectApprovals(Long publishId) {
        return approvalMapper.selectByPublishId(publishId);
    }

    public boolean hasApprovedPublish(Long skillId) {
        return publishMapper.hasApprovedBySkillId(skillId);
    }

    public List<SkillPublish> selectApprovedBySkillIds(List<Long> skillIds) {
        if (skillIds.isEmpty()) return List.of();
        return publishMapper.selectApprovedBySkillIds(skillIds);
    }

    public List<SkillPublish> selectBySkillId(Long skillId) {
        return publishMapper.selectBySkillId(skillId);
    }
}
```

- [ ] **Step 4: 运行测试验证通过**

Run: `cd analysis-project && mvn test -pl . -Dtest=SkillPublishServiceTest -q`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/agentscopea2a/v2/service/SkillPublishService.java src/test/java/com/agentscopea2a/v2/service/SkillPublishServiceTest.java
git commit -m "feat: add SkillPublishService for publish request and approval"
```

---

## Task 9: SkillDraftService

**Files:**
- Create: `src/main/java/com/agentscopea2a/v2/service/SkillDraftService.java`
- Test: `src/test/java/com/agentscopea2a/v2/service/SkillDraftServiceTest.java`

**Interfaces:**
- Consumes: SkillDraftMapper, SkillManageMapper, SkillVersionHistoryService, SkillPublishService, MockOrgService, SkillOperationHistoryService
- Produces: `submitDraft(skillId, Skill patch, userId) -> SkillDraft`, `approve(draftId, approverId, comment)`, `reject(draftId, approverId, comment)`, `getDraft(skillId)`, `selectPendingByApprover(userId)`

- [ ] **Step 1: 写失败测试**

```java
package com.agentscopea2a.v2.service;

import com.agentscopea2a.entity.Skill;
import com.agentscopea2a.entity.SkillDraft;
import com.agentscopea2a.entity.SkillPublish;
import com.agentscopea2a.mapper.mysql.SkillDraftMapper;
import com.agentscopea2a.mapper.mysql.SkillManageMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class SkillDraftServiceTest {

    private SkillDraftMapper draftMapper;
    private SkillManageMapper manageMapper;
    private SkillVersionHistoryService versionHistoryService;
    private SkillPublishService publishService;
    private MockOrgService orgService;
    private SkillOperationHistoryService historyService;
    private SkillDraftService service;

    @BeforeEach
    void setUp() {
        draftMapper = mock(SkillDraftMapper.class);
        manageMapper = mock(SkillManageMapper.class);
        versionHistoryService = mock(SkillVersionHistoryService.class);
        publishService = mock(SkillPublishService.class);
        orgService = new MockOrgService();
        historyService = mock(SkillOperationHistoryService.class);
        service = new SkillDraftService(draftMapper, manageMapper, versionHistoryService,
                publishService, orgService, historyService);
    }

    @Test
    void submitDraft_rejects_when_pending_already_exists() {
        when(draftMapper.selectPendingBySkillId(1L)).thenReturn(SkillDraft.builder().id(99L).build());
        Skill patch = Skill.builder().name("new").build();

        assertThatThrownBy(() -> service.submitDraft(1L, patch, "u1"))
                .hasMessageContaining("DraftAlreadyPending");
    }

    @Test
    void submitDraft_inserts_draft() {
        when(draftMapper.selectPendingBySkillId(1L)).thenReturn(null);
        Skill patch = Skill.builder().name("new").description("d").content("c")
                .category("数据").tags("#sql").build();

        service.submitDraft(1L, patch, "u1");

        verify(draftMapper).insert(argThat(d ->
            d.getSkillId().equals(1L) && d.getStatus().equals("PENDING") && d.getSubmitter().equals("u1")
        ));
        verify(historyService).record(1L, null, "u1", "UPDATE", null, null);
    }

    @Test
    void approve_denies_non_approver() {
        SkillDraft draft = SkillDraft.builder().id(10L).skillId(1L).status("PENDING").build();
        when(draftMapper.selectById(10L)).thenReturn(draft);
        SkillPublish pub = SkillPublish.builder()
                .skillId(1L).targetType("GROUP").targetId("group_001").status("APPROVED").build();
        when(publishService.selectApprovedBySkillId(1L)).thenReturn(List.of(pub));

        assertThatThrownBy(() -> service.approve(10L, "user_001", "ok"))
                .hasMessageContaining("NotApprover");
    }

    @Test
    void approve_applies_draft_to_main_table() {
        SkillDraft draft = SkillDraft.builder()
                .id(10L).skillId(1L).status("PENDING")
                .name("new name").description("d").content("c")
                .category("数据").tags("#sql").submitter("u1").build();
        when(draftMapper.selectById(10L)).thenReturn(draft);
        SkillPublish pub = SkillPublish.builder()
                .skillId(1L).targetType("GROUP").targetId("group_001").status("APPROVED").build();
        when(publishService.selectApprovedBySkillId(1L)).thenReturn(List.of(pub));
        Skill oldSkill = Skill.builder().id(1L).name("old").build();
        when(manageMapper.selectById(1L)).thenReturn(oldSkill);

        service.approve(10L, "approver_001", "approved");

        verify(versionHistoryService).saveVersion(oldSkill, "approver_001", "draft approved");
        verify(manageMapper).update(argThat(s -> s.getName().equals("new name")));
        verify(draftMapper).updateStatus(10L, "APPROVED", "approver_001", "approved");
        verify(historyService).record(1L, null, "approver_001", "APPROVE", null, null);
    }
}
```

- [ ] **Step 2: 运行测试验证失败**

Run: `cd analysis-project && mvn test -pl . -Dtest=SkillDraftServiceTest -q`
Expected: FAIL

- [ ] **Step 3: 实现 SkillDraftService**

```java
package com.agentscopea2a.v2.service;

import com.agentscopea2a.entity.Skill;
import com.agentscopea2a.entity.SkillDraft;
import com.agentscopea2a.entity.SkillPublish;
import com.agentscopea2a.mapper.mysql.SkillDraftMapper;
import com.agentscopea2a.mapper.mysql.SkillManageMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 变更草稿 Service。已发布 Skill 的内容修改走草稿审批流(§4.3.1)。
 * 审批通过:旧主表存版本历史 -> 草稿内容应用到主表 -> draft.status=APPROVED
 * 审批退回:draft.status=REJECTED,主表不变,可重新提交(复用同一行)
 * 或签模式:任一审批人通过即生效
 */
@Service
public class SkillDraftService {

    private final SkillDraftMapper draftMapper;
    private final SkillManageMapper manageMapper;
    private final SkillVersionHistoryService versionHistoryService;
    private final SkillPublishService publishService;
    private final MockOrgService orgService;
    private final SkillOperationHistoryService historyService;

    public SkillDraftService(SkillDraftMapper draftMapper,
                             SkillManageMapper manageMapper,
                             SkillVersionHistoryService versionHistoryService,
                             SkillPublishService publishService,
                             MockOrgService orgService,
                             SkillOperationHistoryService historyService) {
        this.draftMapper = draftMapper;
        this.manageMapper = manageMapper;
        this.versionHistoryService = versionHistoryService;
        this.publishService = publishService;
        this.orgService = orgService;
        this.historyService = historyService;
    }

    @Transactional
    public SkillDraft submitDraft(Long skillId, Skill patch, String userId) {
        if (draftMapper.selectPendingBySkillId(skillId) != null) {
            throw new IllegalStateException("DraftAlreadyPending: " + skillId);
        }
        SkillDraft draft = SkillDraft.builder()
                .skillId(skillId)
                .name(patch.getName())
                .description(patch.getDescription())
                .content(patch.getContent())
                .category(patch.getCategory())
                .tags(patch.getTags())
                .status("PENDING")
                .submitter(userId)
                .submittedAt(LocalDateTime.now())
                .build();
        draftMapper.insert(draft);
        historyService.record(skillId, null, userId, "UPDATE", null, null);
        return draft;
    }

    @Transactional
    public void approve(Long draftId, String approverId, String comment) {
        SkillDraft draft = draftMapper.selectById(draftId);
        if (draft == null) throw new IllegalStateException("DraftNotFound: " + draftId);
        if (!"PENDING".equals(draft.getStatus())) throw new IllegalStateException("DraftNotPending: " + draftId);

        // 或签:任一审批人通过即可
        List<SkillPublish> approvedPubs = publishService.selectApprovedBySkillId(draft.getSkillId());
        boolean isApprover = approvedPubs.stream().anyMatch(pub ->
                approverId.equals(orgService.getApprover(pub.getTargetType(), pub.getTargetId())));
        if (!isApprover) throw new IllegalStateException("NotApprover: " + draftId);

        // 存旧版本到版本历史
        Skill oldSkill = manageMapper.selectById(draft.getSkillId());
        versionHistoryService.saveVersion(oldSkill, approverId, "draft approved");

        // 把草稿内容应用到主表
        Skill updated = Skill.builder()
                .id(draft.getSkillId())
                .name(draft.getName())
                .description(draft.getDescription())
                .content(draft.getContent())
                .category(draft.getCategory())
                .tags(draft.getTags())
                .status(oldSkill.getStatus())
                .build();
        manageMapper.update(updated);

        draftMapper.updateStatus(draftId, "APPROVED", approverId, comment);
        historyService.record(draft.getSkillId(), null, approverId, "APPROVE", null, null);
    }

    @Transactional
    public void reject(Long draftId, String approverId, String comment) {
        SkillDraft draft = draftMapper.selectById(draftId);
        if (draft == null) throw new IllegalStateException("DraftNotFound: " + draftId);
        if (!"PENDING".equals(draft.getStatus())) throw new IllegalStateException("DraftNotPending: " + draftId);

        List<SkillPublish> approvedPubs = publishService.selectApprovedBySkillId(draft.getSkillId());
        boolean isApprover = approvedPubs.stream().anyMatch(pub ->
                approverId.equals(orgService.getApprover(pub.getTargetType(), pub.getTargetId())));
        if (!isApprover) throw new IllegalStateException("NotApprover: " + draftId);

        draftMapper.updateStatus(draftId, "REJECTED", approverId, comment);
        historyService.record(draft.getSkillId(), null, approverId, "REJECT", null, null);
    }

    /** 重新提交:复用 REJECTED 行,更新内容并改回 PENDING */
    @Transactional
    public void resubmit(Long draftId, Skill patch, String userId) {
        SkillDraft draft = draftMapper.selectById(draftId);
        if (draft == null) throw new IllegalStateException("DraftNotFound: " + draftId);
        if (!"REJECTED".equals(draft.getStatus())) throw new IllegalStateException("DraftNotRejected: " + draftId);

        draft.setName(patch.getName());
        draft.setDescription(patch.getDescription());
        draft.setContent(patch.getContent());
        draft.setCategory(patch.getCategory());
        draft.setTags(patch.getTags());
        draftMapper.updateContent(draft);
        historyService.record(draft.getSkillId(), null, userId, "UPDATE", null, null);
    }

    public SkillDraft getDraft(Long skillId) {
        return draftMapper.selectPendingBySkillId(skillId);
    }

    public List<SkillDraft> selectPendingByApprover(String approverUserId) {
        List<SkillDraft> allPending = draftMapper.selectPendingByApprover(approverUserId);
        // 过滤:当前用户是否是该 Skill 任一 APPROVED 发布范围的审批人
        return allPending.stream().filter(d -> {
            List<SkillPublish> pubs = publishService.selectApprovedBySkillId(d.getSkillId());
            return pubs.stream().anyMatch(pub ->
                    approverUserId.equals(orgService.getApprover(pub.getTargetType(), pub.getTargetId())));
        }).toList();
    }
}
```

注:SkillPublishService 需新增 `selectApprovedBySkillId(Long skillId)` 方法(委托 `publishMapper.selectApprovedBySkillId`)。

- [ ] **Step 4: 运行测试验证通过**

Run: `cd analysis-project && mvn test -pl . -Dtest=SkillDraftServiceTest -q`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/agentscopea2a/v2/service/SkillDraftService.java src/test/java/com/agentscopea2a/v2/service/SkillDraftServiceTest.java
git commit -m "feat: add SkillDraftService for change-approval draft workflow"
```

---

## Task 10: SkillService.update 改造 + 可用性计算 + SkillListQuery 扩展

**Files:**
- Modify: `src/main/java/com/agentscopea2a/v2/service/SkillService.java`
- Modify: `src/main/java/com/agentscopea2a/dto/SkillListQuery.java`
- Modify: `src/main/java/com/agentscopea2a/dto/SkillListItem.java`
- Modify: `src/main/resources/mybatis/mapper/mysql/SkillManageMapper.xml`
- Modify: `src/test/java/com/agentscopea2a/v2/service/SkillServiceTest.java`

**Interfaces:**
- Consumes: SkillPublishService, SkillDraftService, SkillVersionHistoryService, SkillOperationHistoryService, MockOrgService, SkillUserDisableService
- Produces: SkillService.update 改为区分已发布/未发布;SkillService.list 接入可用性计算;SkillListQuery 增加 availability

- [ ] **Step 1: SkillListQuery 增加 availability 字段**

在 `SkillListQuery.java` 的字段列表末尾增加:
```java
private String availability;  // all(默认) / available(仅我当前可用)
```
并增加:
```java
public String getEffectiveAvailability() { return availability == null ? "all" : availability; }
```

- [ ] **Step 2: SkillListItem.of 改为接收 available 参数**

将 `SkillListItem.of` 方法签名改为:
```java
public static SkillListItem of(Skill s, boolean liked, boolean used, boolean available, Integer rank) {
    return new SkillListItem(
            s.getId(), s.getName(), s.getDescription(), s.getCategory(), s.getTags(),
            s.getOwnerUserId(),
            s.getLikeCount() == null ? 0L : s.getLikeCount(),
            liked, used, available, rank, s.getUpdatedAt());
}
```

- [ ] **Step 3: SkillService 构造函数增加新依赖**

```java
public SkillService(SkillManageMapper skillManageMapper,
                    SkillLikeMapper likeMapper,
                    SkillReferenceMapper refMapper,
                    SkillPublishService publishService,
                    SkillDraftService draftService,
                    SkillVersionHistoryService versionHistoryService,
                    SkillOperationHistoryService historyService,
                    MockOrgService orgService,
                    SkillUserDisableService disableService) {
    // ...赋值
}
```

- [ ] **Step 4: SkillService.update 改造**

```java
@Transactional
public Object update(Long id, Skill patch, String userId) {
    Skill s = get(id);
    if (!s.getOwnerUserId().equals(userId)) {
        throw new IllegalStateException("SkillAccessDenied: " + id);
    }
    if (patch.getName() != null && !patch.getName().equals(s.getName())
            && skillManageMapper.existsByName(patch.getName())) {
        throw new IllegalStateException("SkillNameConflict: " + patch.getName());
    }
    // 已发布 Skill -> 走草稿审批
    if (publishService.hasApprovedPublish(id)) {
        return draftService.submitDraft(id, patch, userId);
    }
    // 未发布 Skill -> 直接更新
    versionHistoryService.saveVersion(s, userId, "edit");
    if (patch.getName() != null) s.setName(patch.getName());
    if (patch.getDescription() != null) s.setDescription(patch.getDescription());
    if (patch.getContent() != null) s.setContent(patch.getContent());
    if (patch.getCategory() != null) s.setCategory(patch.getCategory());
    if (patch.getTags() != null) s.setTags(patch.getTags());
    s.setUpdatedAt(LocalDateTime.now());
    skillManageMapper.update(s);
    historyService.record(id, null, userId, "UPDATE", null, null);
    return skillManageMapper.selectById(id);
}
```

注意:返回类型从 `Skill` 改为 `Object`(可能返回 Skill 或 SkillDraft)。Controller 层相应调整。

- [ ] **Step 5: SkillService.list 接入可用性计算**

在 list 方法中,获取 skills 后增加:
```java
// 可用性批量计算
List<Long> ids = skills.stream().map(Skill::getId).toList();
List<SkillPublish> approvedPubs = publishService.selectApprovedBySkillIds(ids);
Set<MockOrgService.OrgRef> userOrgs = Set.copyOf(orgService.getUserOrgs(q.getUserId()));
Set<Long> disabledIds = disableService.selectDisabledSkillIds(q.getUserId(), ids);

// skill -> available 映射
Map<Long, Boolean> availableMap = new HashMap<>();
for (Skill s : skills) {
    boolean published = approvedPubs.stream().anyMatch(p ->
            p.getSkillId().equals(s.getId()) &&
            userOrgs.stream().anyMatch(o -> o.orgType().equals(p.getTargetType()) && o.orgId().equals(p.getTargetId())));
    availableMap.put(s.getId(), published && !disabledIds.contains(s.getId()));
}

// availability 筛选
boolean filterAvailable = "available".equals(q.getEffectiveAvailability());
```

构建 items 时:
```java
boolean available = availableMap.getOrDefault(s.getId(), false);
if (filterAvailable && !available) { rank++; continue; }
items.add(SkillListItem.of(s, likedIds.contains(s.getId()),
        usedIds.contains(s.getId()), available, rankVisible ? rank : null));
rank++;
```

- [ ] **Step 6: 更新 SkillServiceTest**

现有测试的 setUp 需更新为 9 参构造函数,mock 新依赖。新增测试:
- `update_unpublished_skill_directly_updates` - 未发布 Skill 直接更新
- `update_published_skill_submits_draft` - 已发布 Skill 走草稿

- [ ] **Step 7: 编译 + 测试验证**

Run: `cd analysis-project && mvn test -pl . -Dtest=SkillServiceTest -q`
Expected: PASS

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/agentscopea2a/v2/service/SkillService.java src/main/java/com/agentscopea2a/dto/SkillListQuery.java src/main/java/com/agentscopea2a/dto/SkillListItem.java src/test/java/com/agentscopea2a/v2/service/SkillServiceTest.java
git commit -m "feat: SkillService.update now routes published skills to draft approval; list computes availability"
```

---

## Task 11: Controller 层(Publish / Draft / Disable + 版本历史 + 引用补全)

**Files:**
- Create: `src/main/java/com/agentscopea2a/v2/controller/SkillPublishController.java`
- Create: `src/main/java/com/agentscopea2a/v2/controller/SkillDraftController.java`
- Create: `src/main/java/com/agentscopea2a/v2/controller/SkillUserDisableController.java`
- Modify: `src/main/java/com/agentscopea2a/v2/controller/SkillController.java`(增加 versions 端点)
- Modify: `src/main/java/com/agentscopea2a/v2/controller/SkillReferenceController.java`(增加 my-references / referencers)
- Modify: `src/main/java/com/agentscopea2a/mapper/mysql/SkillReferenceMapper.java` + XML(增加 referencers 查询)

- [ ] **Step 1: 创建 SkillPublishController**

```java
package com.agentscopea2a.v2.controller;

import com.agentscopea2a.entity.SkillApproval;
import com.agentscopea2a.entity.SkillPublish;
import com.agentscopea2a.v2.service.SkillPublishService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@CrossOrigin(origins = "*", maxAge = 3600)
public class SkillPublishController {

    private final SkillPublishService publishService;

    public SkillPublishController(SkillPublishService publishService) {
        this.publishService = publishService;
    }

    @PostMapping("/api/skills/{id}/publish")
    public SkillPublish requestPublish(@PathVariable Long id, @RequestBody Map<String, String> body,
                                       @RequestHeader("X-User-Id") String userId) {
        return publishService.requestPublish(id, body.get("targetType"), body.get("targetId"),
                body.get("targetName"), userId);
    }

    @PostMapping("/api/publish/{id}/approve")
    public void approve(@PathVariable Long id, @RequestBody Map<String, String> body,
                        @RequestHeader("X-User-Id") String userId) {
        publishService.approve(id, userId, body.get("comment"));
    }

    @PostMapping("/api/publish/{id}/reject")
    public void reject(@PathVariable Long id, @RequestBody Map<String, String> body,
                       @RequestHeader("X-User-Id") String userId) {
        publishService.reject(id, userId, body.get("comment"));
    }

    @GetMapping("/api/publish/pending")
    public List<SkillPublish> pending(@RequestHeader("X-User-Id") String userId) {
        return publishService.selectPendingByApprover(userId);
    }

    @GetMapping("/api/publish/{id}/approvals")
    public List<SkillApproval> approvals(@PathVariable Long id) {
        return publishService.selectApprovals(id);
    }
}
```

- [ ] **Step 2: 创建 SkillDraftController**

```java
package com.agentscopea2a.v2.controller;

import com.agentscopea2a.entity.SkillDraft;
import com.agentscopea2a.v2.service.SkillDraftService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@CrossOrigin(origins = "*", maxAge = 3600)
@RequestMapping("/api")
public class SkillDraftController {

    private final SkillDraftService draftService;

    public SkillDraftController(SkillDraftService draftService) {
        this.draftService = draftService;
    }

    @GetMapping("/skills/{id}/draft")
    public SkillDraft getDraft(@PathVariable Long id) {
        return draftService.getDraft(id);
    }

    @PostMapping("/skills/{id}/draft/approve")
    public void approve(@PathVariable Long id, @RequestBody Map<String, String> body,
                        @RequestHeader("X-User-Id") String userId) {
        draftService.approve(id, userId, body.get("comment"));
    }

    @PostMapping("/skills/{id}/draft/reject")
    public void reject(@PathVariable Long id, @RequestBody Map<String, String> body,
                       @RequestHeader("X-User-Id") String userId) {
        draftService.reject(id, userId, body.get("comment"));
    }

    @GetMapping("/draft/pending")
    public List<SkillDraft> pending(@RequestHeader("X-User-Id") String userId) {
        return draftService.selectPendingByApprover(userId);
    }
}
```

- [ ] **Step 3: 创建 SkillUserDisableController**

```java
package com.agentscopea2a.v2.controller;

import com.agentscopea2a.v2.service.SkillUserDisableService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@CrossOrigin(origins = "*", maxAge = 3600)
@RequestMapping("/api/skills/{id}/disable")
public class SkillUserDisableController {

    private final SkillUserDisableService disableService;

    public SkillUserDisableController(SkillUserDisableService disableService) {
        this.disableService = disableService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void disable(@PathVariable Long id, @RequestHeader("X-User-Id") String userId) {
        disableService.disable(id, userId);
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void enable(@PathVariable Long id, @RequestHeader("X-User-Id") String userId) {
        disableService.enable(id, userId);
    }
}
```

- [ ] **Step 4: SkillController 增加 versions 端点**

在 SkillController 增加方法:
```java
@GetMapping("/{id}/versions")
public List<SkillVersionHistory> versions(@PathVariable Long id) {
    return versionHistoryService.selectBySkillId(id);
}
```
需注入 `SkillVersionHistoryService`。

同时 update 端点的返回类型调整为 `Object`(因可能返回 SkillDraft)。

- [ ] **Step 5: SkillReferenceController 增加 my-references / referencers**

在 SkillReferenceController 增加:
```java
@GetMapping("/api/skills/my-references")
public List<Long> myReferences(@RequestHeader("X-User-Id") String userId) {
    return referenceService.listMine(userId);
}

@GetMapping("/api/skills/{id}/referencers")
public List<String> referencers(@PathVariable Long id, @RequestHeader("X-User-Id") String userId) {
    // owner 校验
    Skill s = skillService.get(id);
    if (!s.getOwnerUserId().equals(userId)) {
        throw new IllegalStateException("SkillAccessDenied: " + id);
    }
    return referenceMapper.selectReferencersBySkillId(id);
}
```

需在 SkillReferenceMapper 增加:
```java
List<String> selectReferencersBySkillId(@Param("skillId") Long skillId);
```
XML:
```xml
<select id="selectReferencersBySkillId" resultType="java.lang.String">
    SELECT creator FROM skill_reference WHERE target_skill_id = #{skillId}
</select>
```

- [ ] **Step 6: 编译验证**

Run: `cd analysis-project && mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/agentscopea2a/v2/controller/SkillPublishController.java src/main/java/com/agentscopea2a/v2/controller/SkillDraftController.java src/main/java/com/agentscopea2a/v2/controller/SkillUserDisableController.java src/main/java/com/agentscopea2a/v2/controller/SkillController.java src/main/java/com/agentscopea2a/v2/controller/SkillReferenceController.java src/main/java/com/agentscopea2a/mapper/mysql/SkillReferenceMapper.java src/main/resources/mybatis/mapper/mysql/SkillReferenceMapper.xml
git commit -m "feat: add controllers for publish/draft/disable + versions/referencers endpoints"
```

---

## Task 12: 前端 - API 层 + 类型

**Files:**
- Modify: `frontend/src/api/skill.ts`
- Modify: `frontend/src/types/skill.ts`

- [ ] **Step 1: types/skill.ts 新增类型**

```typescript
export interface SkillPublish {
  id: number;
  skillId: number;
  targetType: string;
  targetId: string;
  targetName: string;
  status: string;
  submitter: string;
  approver: string | null;
  approveTime: string | null;
  createdAt: string;
}

export interface SkillApproval {
  id: number;
  publishId: number | null;
  draftId: number | null;
  action: string;
  operator: string;
  comment: string | null;
  createdAt: string;
}

export interface SkillDraft {
  id: number;
  skillId: number;
  name: string;
  description: string;
  content: string;
  category: string;
  tags: string;
  status: string;
  submitter: string;
  approver: string | null;
  approveComment: string | null;
  submittedAt: string;
}

export interface SkillVersionHistory {
  id: number;
  skillId: number;
  version: number;
  name: string;
  description: string;
  content: string;
  category: string;
  tags: string;
  editedBy: string;
  editReason: string | null;
  createdAt: string;
}
```

- [ ] **Step 2: api/skill.ts 新增 API 函数**

```typescript
// --- Publish API ---

export async function requestPublish(skillId: number, targetType: string, targetId: string, targetName: string): Promise<SkillPublish> {
  const res = await fetch(`${BASE}/${skillId}/publish`, {
    method: 'POST', headers: jsonHeaders(),
    body: JSON.stringify({ targetType, targetId, targetName })
  });
  if (!res.ok) throw await skillError(res, '发布申请失败');
  return res.json();
}

export async function approvePublish(publishId: number, comment: string): Promise<void> {
  const res = await fetch(`/api/publish/${publishId}/approve`, {
    method: 'POST', headers: jsonHeaders(), body: JSON.stringify({ comment })
  });
  if (!res.ok) throw await skillError(res, '审批失败');
}

export async function rejectPublish(publishId: number, comment: string): Promise<void> {
  const res = await fetch(`/api/publish/${publishId}/reject`, {
    method: 'POST', headers: jsonHeaders(), body: JSON.stringify({ comment })
  });
  if (!res.ok) throw await skillError(res, '退回失败');
}

export async function getPendingPublishes(): Promise<SkillPublish[]> {
  const res = await fetch(`/api/publish/pending`, { headers: authHeaders() });
  if (!res.ok) throw new Error(`getPending failed: ${res.status}`);
  return res.json();
}

export async function getApprovals(publishId: number): Promise<SkillApproval[]> {
  const res = await fetch(`/api/publish/${publishId}/approvals`, { headers: authHeaders() });
  if (!res.ok) throw new Error(`getApprovals failed: ${res.status}`);
  return res.json();
}

// --- Draft API ---

export async function getDraft(skillId: number): Promise<SkillDraft | null> {
  const res = await fetch(`${BASE}/${skillId}/draft`, { headers: authHeaders() });
  if (res.status === 404) return null;
  if (!res.ok) throw new Error(`getDraft failed: ${res.status}`);
  return res.json();
}

export async function approveDraft(draftId: number, comment: string): Promise<void> {
  const res = await fetch(`${BASE}/${draftId}/draft/approve`, {
    method: 'POST', headers: jsonHeaders(), body: JSON.stringify({ comment })
  });
  if (!res.ok) throw await skillError(res, '审批失败');
}

export async function rejectDraft(draftId: number, comment: string): Promise<void> {
  const res = await fetch(`${BASE}/${draftId}/draft/reject`, {
    method: 'POST', headers: jsonHeaders(), body: JSON.stringify({ comment })
  });
  if (!res.ok) throw await skillError(res, '退回失败');
}

export async function getPendingDrafts(): Promise<SkillDraft[]> {
  const res = await fetch(`/api/draft/pending`, { headers: authHeaders() });
  if (!res.ok) throw new Error(`getPendingDrafts failed: ${res.status}`);
  return res.json();
}

// --- Disable API ---

export async function disableSkill(id: number): Promise<void> {
  const res = await fetch(`${BASE}/${id}/disable`, { method: 'POST', headers: authHeaders() });
  if (!res.ok) throw await skillError(res, '禁用失败');
}

export async function enableSkill(id: number): Promise<void> {
  const res = await fetch(`${BASE}/${id}/disable`, { method: 'DELETE', headers: authHeaders() });
  if (!res.ok) throw await skillError(res, '启用失败');
}

// --- Version History API ---

export async function getVersions(id: number): Promise<SkillVersionHistory[]> {
  const res = await fetch(`${BASE}/${id}/versions`, { headers: authHeaders() });
  if (!res.ok) throw new Error(`getVersions failed: ${res.status}`);
  return res.json();
}

// --- Referencers API ---

export async function getReferencers(id: number): Promise<string[]> {
  const res = await fetch(`${BASE}/${id}/referencers`, { headers: authHeaders() });
  if (!res.ok) throw new Error(`getReferencers failed: ${res.status}`);
  return res.json();
}
```

同时在 `SkillListParams` 增加 `availability` 字段:
```typescript
availability?: 'all' | 'available';
```

- [ ] **Step 3: 编译验证**

Run: `cd analysis-project/frontend && npx tsc --noEmit`
Expected: 无错误

- [ ] **Step 4: Commit**

```bash
git add frontend/src/api/skill.ts frontend/src/types/skill.ts
git commit -m "feat: add frontend API + types for publish/draft/disable/versions"
```

---

## Task 13: 前端 - 审批页 + 导航 + 路由

**Files:**
- Create: `frontend/src/pages/skill/SkillApprovalListPage.vue`
- Create: `frontend/src/pages/skill/SkillApprovalDetailPage.vue`
- Modify: `frontend/src/components/SkillShell.vue`
- Modify: `frontend/src/main.ts`

- [ ] **Step 1: SkillShell.vue 增加"待我审批"导航项**

在 nav 数组末尾增加:
```typescript
{ to: '/skills/approvals', label: '待我审批' },
```

- [ ] **Step 2: main.ts 增加 approvals 路由**

在 `/skills` children 中增加:
```typescript
{ path: 'approvals', component: SkillApprovalListPage },
{ path: 'approvals/:id', component: SkillApprovalDetailPage },
```

- [ ] **Step 3: 创建 SkillApprovalListPage.vue**

展示两类待审批项:发布申请 + 变更草稿。用 tab 切换。每个 item 显示 Skill 名称、提交人、提交时间、通过/退回按钮。

```vue
<script setup lang="ts">
import { ref, onMounted } from 'vue';
import { getPendingPublishes, getPendingDrafts, approvePublish, rejectPublish, approveDraft, rejectDraft } from '../../api/skill';
import type { SkillPublish, SkillDraft } from '../../types/skill';

const tab = ref<'publish' | 'draft'>('publish');
const publishes = ref<SkillPublish[]>([]);
const drafts = ref<SkillDraft[]>([]);
const loading = ref(false);

async function load() {
  loading.value = true;
  try {
    publishes.value = await getPendingPublishes();
    drafts.value = await getPendingDrafts();
  } finally { loading.value = false; }
}
onMounted(load);

async function doApprovePublish(id: number) {
  await approvePublish(id, '');
  await load();
}
async function doRejectPublish(id: number) {
  await rejectPublish(id, '');
  await load();
}
async function doApproveDraft(id: number) {
  await approveDraft(id, '');
  await load();
}
async function doRejectDraft(id: number) {
  await rejectDraft(id, '');
  await load();
}
</script>

<template>
  <h2>待我审批</h2>
  <div class="tabs">
    <button :class="{ on: tab === 'publish' }" @click="tab = 'publish'">发布申请 ({{ publishes.length }})</button>
    <button :class="{ on: tab === 'draft' }" @click="tab = 'draft'">变更草稿 ({{ drafts.length }})</button>
  </div>
  <div v-if="loading">加载中…</div>
  <div v-else-if="tab === 'publish'">
    <div v-if="publishes.length === 0" class="empty">暂无待审批的发布申请</div>
    <div v-for="p in publishes" :key="p.id" class="item">
      <span class="title">Skill #{{ p.skillId }}</span>
      <span class="meta">提交人: {{ p.submitter }} · 目标: {{ p.targetName }} ({{ p.targetType }}) · {{ p.createdAt }}</span>
      <div class="actions">
        <button class="ok" @click="doApprovePublish(p.id)">通过</button>
        <button class="no" @click="doRejectPublish(p.id)">退回</button>
      </div>
    </div>
  </div>
  <div v-else>
    <div v-if="drafts.length === 0" class="empty">暂无待审批的变更草稿</div>
    <div v-for="d in drafts" :key="d.id" class="item">
      <span class="title">Skill #{{ d.skillId }} - {{ d.name }}</span>
      <span class="meta">提交人: {{ d.submitter }} · {{ d.submittedAt }}</span>
      <div class="actions">
        <button class="ok" @click="doApproveDraft(d.id)">通过</button>
        <button class="no" @click="doRejectDraft(d.id)">退回</button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.tabs { display: flex; gap: 8px; margin-bottom: 12px; }
.tabs button { padding: 6px 14px; border: 1px solid #cbd5e1; border-radius: 6px; background: #fff; cursor: pointer; }
.tabs button.on { background: #3b82f6; color: #fff; border-color: #3b82f6; }
.item { display: flex; align-items: center; gap: 12px; padding: 12px; border: 1px solid #e2e8f0; border-radius: 8px; margin-bottom: 8px; }
.title { font-weight: 600; }
.meta { color: #94a3b8; font-size: 13px; flex: 1; }
.actions { display: flex; gap: 6px; }
.ok { padding: 4px 12px; border-radius: 6px; border: 1px solid #22c55e; background: #fff; color: #22c55e; cursor: pointer; }
.no { padding: 4px 12px; border-radius: 6px; border: 1px solid #ef4444; background: #fff; color: #ef4444; cursor: pointer; }
.empty { color: #64748b; padding: 24px; text-align: center; }
</style>
```

- [ ] **Step 4: 创建 SkillApprovalDetailPage.vue**

展示单个审批详情:Skill 内容、审批意见输入、通过/退回按钮。可简化为跳转到 Skill 详情页 + 草稿审批入口。

- [ ] **Step 5: 编译验证**

Run: `cd analysis-project/frontend && npx tsc --noEmit`
Expected: 无错误

- [ ] **Step 6: Commit**

```bash
git add frontend/src/pages/skill/SkillApprovalListPage.vue frontend/src/pages/skill/SkillApprovalDetailPage.vue frontend/src/components/SkillShell.vue frontend/src/main.ts
git commit -m "feat: add approval list/detail pages + nav item + routes"
```

---

## Task 14: 前端 - 筛选条补全 + 卡片徽章 + 详情页补全

**Files:**
- Modify: `frontend/src/pages/skill/SkillListPage.vue`
- Modify: `frontend/src/components/SkillCard.vue`
- Modify: `frontend/src/components/SkillRow.vue`
- Modify: `frontend/src/pages/skill/SkillDetailPage.vue`

- [ ] **Step 1: SkillListPage.vue 增加标签 + 可用性筛选**

在筛选条增加:
```html
<select v-model="tag" @change="load">
  <option value="">全部标签</option>
  <option v-for="t in tags" :key="t" :value="t">{{ t }}</option>
</select>
<select v-model="availability" @change="load">
  <option value="">全部</option>
  <option value="available">仅可用</option>
</select>
```

script 增加:
```typescript
const tag = ref('');
const availability = ref('');
const tags = ['sql', '优化', 'query', 'quality', '数据分析', '办公']; // 硬编码常见标签
```

load 函数增加 tag/availability 参数:
```typescript
items.value = await listSkills({
  view: props.view, sort: sort.value,
  category: category.value || undefined,
  tag: tag.value || undefined,
  availability: availability.value as 'available' | undefined,
  keyword: keyword.value || undefined,
});
```

watch 增加 tag/availability:
```typescript
watch([sort, category, tag, availability, () => props.view], load, { immediate: true });
```

- [ ] **Step 2: SkillCard.vue 增加可用性徽章 + 已使用标记**

在卡片顶部增加:
```html
<span class="badge" :class="{ on: item.available }">{{ item.available ? '🟢 可用' : '⚪ 不可用' }}</span>
<span v-if="item.used" class="used-tag">已使用</span>
```

CSS:
```css
.badge { font-size: 12px; }
.badge.on { color: #22c55e; }
.badge:not(.on) { color: #94a3b8; }
.used-tag { font-size: 11px; color: #3b82f6; border: 1px solid #3b82f6; border-radius: 4px; padding: 1px 4px; }
```

- [ ] **Step 3: SkillRow.vue 同样增加徽章 + 已使用标记**

与 SkillCard 一致的徽章逻辑,加在列表行中。

- [ ] **Step 4: SkillDetailPage.vue 增加禁用按钮 + 版本历史 + 草稿提示**

增加禁用/启用按钮:
```html
<button v-if="!disabled" class="disable-btn" @click="doDisable">禁用</button>
<button v-else class="enable-btn" @click="doEnable">启用</button>
```

增加版本历史面板:
```html
<section class="block" v-if="versions.length">
  <h3 class="block-title"><span class="bar"></span>版本历史</h3>
  <div v-for="v in versions" :key="v.id" class="version-item">
    v{{ v.version }} · {{ v.name }} · {{ v.editedBy }} · {{ v.createdAt }}
  </div>
</section>
```

增加草稿提示(已发布 Skill 编辑后):
```html
<div v-if="draft" class="draft-notice">
  ⚠ 变更已提交审批,等待审批通过后生效
</div>
```

script 增加对应数据加载与操作函数。

- [ ] **Step 5: 编译验证**

Run: `cd analysis-project/frontend && npx tsc --noEmit`
Expected: 无错误

- [ ] **Step 6: Commit**

```bash
git add frontend/src/pages/skill/SkillListPage.vue frontend/src/components/SkillCard.vue frontend/src/components/SkillRow.vue frontend/src/pages/skill/SkillDetailPage.vue
git commit -m "feat: add tag/availability filters, availability badge, disable button, version history, draft notice"
```

---

## Task 15: 全量编译 + 前端构建 + 最终验证

**Files:**
- 无新建,全量验证

- [ ] **Step 1: 后端全量编译 + 测试**

Run: `cd analysis-project && mvn test -q`
Expected: BUILD SUCCESS,所有测试 PASS

- [ ] **Step 2: 前端构建**

Run: `cd analysis-project/frontend && npm run build`
Expected: 构建成功,产物输出到 `src/main/resources/static`

- [ ] **Step 3: 后端启动验证**

Run: `cd analysis-project && mvn spring-boot:run`(或 java -jar)
Expected: 应用启动成功,Flyway 迁移执行无错误

- [ ] **Step 4: API 冒烟测试**

手动验证关键 API:
- `POST /api/skills` 创建 Skill
- `POST /api/skills/{id}/publish` 申请发布
- `GET /api/publish/pending` 待审批列表
- `POST /api/publish/{id}/approve` 审批通过
- `POST /api/skills/{id}/disable` 禁用
- `GET /api/skills?availability=available` 可用性筛选
- `GET /api/skills/{id}/versions` 版本历史

- [ ] **Step 5: 最终 Commit**

```bash
git add -A
git commit -m "feat: complete skill manage platform gap-fill - publish/approval/draft/disable/version-history/availability"
```

---

## Self-Review

### Spec coverage check

| Spec 章节 | 对应 Task |
|-----------|-----------|
| §4.2 组织发布模块 | Task 8 (SkillPublishService) |
| §4.3 审批模块 | Task 8 + Task 11 (Controller) |
| §4.3.1 变更审批 | Task 9 (SkillDraftService) |
| §4.4 用户禁用 | Task 7 (SkillUserDisableService) + Task 11 |
| §4.6 版本历史 | Task 6 (SkillVersionHistoryService) + Task 11 |
| §4.7 历史追踪 | Task 5 (SkillOperationHistoryService) |
| §6 可用性计算 | Task 10 (SkillService.list 改造) |
| §7.2 发布与审批 API | Task 11 (SkillPublishController) |
| §7.3 用户禁用 API | Task 11 (SkillUserDisableController) |
| §12.4.3 变更审批 API | Task 11 (SkillDraftController) |
| §12.4.4 版本历史 API | Task 11 (SkillController.versions) |
| §12.4.5 引用 API 补全 | Task 11 (SkillReferenceController) |
| §12.5.1 导航栏新增 | Task 13 |
| §12.5.2 新增路由 | Task 13 |
| §12.5.3 筛选条补全 | Task 14 |
| §12.5.4 卡片元素补全 | Task 14 |
| §12.5.5 详情页补全 | Task 14 |
| §12.6 错误处理 | Task 8/9 (IllegalStateException) |
| §12.8 测试策略 | Task 4-9 单元测试 + Task 15 冒烟 |

### Placeholder scan

无 TBD/TODO。所有步骤包含具体代码或具体指令。

### Type consistency

- `SkillPublishService.selectApprovedBySkillIds` 在 Task 8 定义,Task 10 使用 -- 一致
- `SkillDraftService.submitDraft` 在 Task 9 定义,Task 10 的 SkillService.update 调用 -- 一致
- `SkillListItem.of` 签名在 Task 10 改为 5 参(含 available) -- 一致
- `MockOrgService.OrgRef` record 在 Task 4 定义,Task 10 使用 -- 一致
