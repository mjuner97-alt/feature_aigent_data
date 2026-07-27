# Skill 管理平台修复实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修复 Skill 管理平台 5 个问题:可用性计算 bug、列表状态徽章缺失、维度数据不全、身份切换器体验差、补全未实现功能。

**Architecture:** 方案 A 统一修复。核心变更是将"可用"从"发布到组织+未禁用"重新定义为"引用即可用",同时补全维度数据、优化前端展示。

**Tech Stack:** Java 17 + Spring Boot + MyBatis + Flyway(后端);Vue 3 + TypeScript + Vite(前端)

## Global Constraints

- userId 继续从 `X-User-Id` 请求头传入(无 Spring Security,本次不改动)
- 数据库变更通过 Flyway 迁移脚本
- 前端沿用现有 Vue 3 + vue-router 技术栈,不新增独立工程
- 后端包路径 `com.agentscopea2a.*`,Mapper 受 `MySQLConfig.@MapperScan(basePackages = "com.agentscopea2a.mapper.mysql")` 约束
- 前端 API 调用统一走 `api/skill.ts`,userId 从 `localStorage.getItem('skill-user-id') || 'demo-user'` 读取

---

### Task 1: 后端 - MockOrgService 补全用户四维归属

**Files:**
- Modify: `analysis-project/src/main/java/com/agentscopea2a/v2/service/MockOrgService.java:43-51`

**Interfaces:**
- Produces: `MockOrgService.USER_ORGS` 补全为四维归属,供 Task 3 的 `available` 计算与 Task 6 的 `getUserInfo` 使用

- [ ] **Step 1: 修改 USER_ORGS 映射,补全四维归属**

将 `MockOrgService.java` 的 `USER_ORGS` 从只归属 GROUP 改为每个用户同时归属小组+部门+产品线+公司。

修改 `USER_ORGS` (约第 43-51 行):

```java
// user -> orgs 模拟映射(每个用户同时归属小组+部门+产品线+公司)
private static final Map<String, List<OrgRef>> USER_ORGS = Map.of(
    "user_001", List.of(
        new OrgRef("GROUP","group_001"),
        new OrgRef("DEPARTMENT","dept_001"),
        new OrgRef("PRODUCT_LINE","pl_001"),
        new OrgRef("COMPANY","hangyan")),
    "user_002", List.of(
        new OrgRef("GROUP","group_001"),
        new OrgRef("DEPARTMENT","dept_001"),
        new OrgRef("PRODUCT_LINE","pl_001"),
        new OrgRef("COMPANY","hangyan")),
    "user_003", List.of(
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
    "demo-user", List.of(
        new OrgRef("GROUP","group_001"),
        new OrgRef("DEPARTMENT","dept_001"),
        new OrgRef("PRODUCT_LINE","pl_001"),
        new OrgRef("COMPANY","hangyan"))
);
```

- [ ] **Step 2: 编译验证**

Run: `cd analysis-project ; mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add analysis-project/src/main/java/com/agentscopea2a/v2/service/MockOrgService.java
git commit -m "feat: complete user four-dimension org mapping in MockOrgService"
```

---

### Task 2: 后端 - SkillListItem 新增 disabled 字段

**Files:**
- Modify: `analysis-project/src/main/java/com/agentscopea2a/dto/SkillListItem.java`

**Interfaces:**
- Produces: `SkillListItem` record 新增 `disabled` 字段,供 Task 3 填充、Task 7/8 前端展示

- [ ] **Step 1: 在 SkillListItem record 新增 disabled 字段**

修改 `SkillListItem.java` 的 record 定义,在 `available` 后增加 `disabled`:

```java
public record SkillListItem(
        Long id, String name, String description, String category, String tags,
        String ownerUserId, long likeCount, boolean liked, boolean used,
        boolean available, boolean disabled, Integer rank, LocalDateTime updatedAt,
        String dimension
) {
    public static SkillListItem of(Skill s, boolean liked, boolean used, Integer rank) {
        return of(s, liked, used, true, false, rank, "PERSONAL");
    }

    public static SkillListItem of(Skill s, boolean liked, boolean used, boolean available, boolean disabled, Integer rank) {
        return of(s, liked, used, available, disabled, rank, "PERSONAL");
    }

    public static SkillListItem of(Skill s, boolean liked, boolean used, boolean available, boolean disabled, Integer rank, String dimension) {
        return new SkillListItem(
                s.getId(), s.getName(), s.getDescription(), s.getCategory(), s.getTags(),
                s.getOwnerUserId(),
                s.getLikeCount() == null ? 0L : s.getLikeCount(),
                liked, used, available, disabled, rank, s.getUpdatedAt(), dimension);
    }
}
```

- [ ] **Step 2: 编译验证**

Run: `cd analysis-project ; mvn compile -q`
Expected: BUILD SUCCESS(此时 SkillService 调用会报错,因为签名变了,下一个 Task 修复)

- [ ] **Step 3: Commit**

```bash
git add analysis-project/src/main/java/com/agentscopea2a/dto/SkillListItem.java
git commit -m "feat: add disabled field to SkillListItem"
```

---

### Task 3: 后端 - SkillService 重构 available 计算

**Files:**
- Modify: `analysis-project/src/main/java/com/agentscopea2a/v2/service/SkillService.java:40-101`

**Interfaces:**
- Consumes: `SkillUserDisableMapper.selectDisabledSkillIds` (已存在)、`SkillReferenceMapper.selectUsedSkillIds` (已存在)
- Produces: `available = used && !disabled`、移除 `availability` 筛选

- [ ] **Step 1: 注入 SkillUserDisableMapper**

在 `SkillService` 类的字段区(约第 43-48 行)新增 `SkillUserDisableMapper` 字段,并修改构造函数:

```java
private final SkillManageMapper skillManageMapper;
private final SkillLikeMapper likeMapper;
private final SkillReferenceMapper refMapper;
private final SkillPublishService publishService;
private final SkillPublishMapper publishMapper;
private final MockOrgService mockOrgService;
private final SkillUserDisableMapper userDisableMapper;

public SkillService(SkillManageMapper skillManageMapper,
                    SkillLikeMapper likeMapper,
                    SkillReferenceMapper refMapper,
                    @Lazy SkillPublishService publishService,
                    SkillPublishMapper publishMapper,
                    MockOrgService mockOrgService,
                    SkillUserDisableMapper userDisableMapper) {
    this.skillManageMapper = skillManageMapper;
    this.likeMapper = likeMapper;
    this.refMapper = refMapper;
    this.publishService = publishService;
    this.publishMapper = publishMapper;
    this.mockOrgService = mockOrgService;
    this.userDisableMapper = userDisableMapper;
}
```

在 import 区新增:
```java
import com.agentscopea2a.mapper.mysql.SkillUserDisableMapper;
```

- [ ] **Step 2: 重构 list 方法的 available 计算**

将 `list` 方法中约第 64-101 行替换为:

```java
public List<SkillListItem> list(SkillListQuery q) {
    List<Skill> skills = skillManageMapper.selectList(q);
    if (skills.isEmpty()) {
        return List.of();
    }
    List<Long> ids = skills.stream().map(Skill::getId).toList();
    Set<Long> likedIds = nullToEmpty(likeMapper.selectLikedSkillIds(q.getUserId(), ids));
    Set<Long> usedIds = nullToEmpty(refMapper.selectUsedSkillIds(q.getUserId(), ids));
    Set<Long> disabledIds = nullToEmpty(userDisableMapper.selectDisabledSkillIds(q.getUserId(), ids));
    List<SkillPublish> approved = publishMapper.selectApprovedBySkillIds(ids);
    // skill -> 维度类型映射(取该 Skill 的第一条 APPROVED 发布记录的 targetType,仅作展示用)
    java.util.Map<Long, String> skillDimension = new java.util.HashMap<>();
    if (approved != null) {
        for (SkillPublish p : approved) {
            skillDimension.putIfAbsent(p.getSkillId(), p.getTargetType());
        }
    }
    boolean rankVisible = "popular".equals(q.getEffectiveView());
    int rank = q.getEffectiveOffset() + 1;
    List<SkillListItem> items = new ArrayList<>(skills.size());
    for (Skill s : skills) {
        boolean used = usedIds.contains(s.getId());
        boolean disabled = disabledIds.contains(s.getId());
        // 引用即可用:已引用 AND 未禁用
        boolean available = used && !disabled;
        String dim = skillDimension.getOrDefault(s.getId(), "PERSONAL");
        items.add(SkillListItem.of(s, likedIds.contains(s.getId()),
                used, available, disabled, rankVisible ? rank : null, dim));
        rank++;
    }
    // 维度筛选(仅作展示分类,不影响可用性)
    if (q.getDimension() != null && !q.getDimension().isEmpty()) {
        String wantDim = q.getDimension();
        items = items.stream().filter(it -> it.dimension().equals(wantDim)).toList();
    }
    return items;
}
```

- [ ] **Step 3: 编译验证**

Run: `cd analysis-project ; mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add analysis-project/src/main/java/com/agentscopea2a/v2/service/SkillService.java
git commit -m "fix: redefine available as used && !disabled, remove availability filter"
```

---

### Task 4: 后端 - 移除 availability 参数

**Files:**
- Modify: `analysis-project/src/main/java/com/agentscopea2a/v2/controller/SkillController.java:56-69`
- Modify: `analysis-project/src/main/java/com/agentscopea2a/dto/SkillListQuery.java`

**Interfaces:**
- Produces: `GET /api/skills` 不再接受 `availability` 参数

- [ ] **Step 1: 从 SkillController.list 移除 availability 参数**

修改 `SkillController.java` 的 `list` 方法(约第 56-69 行),删除 `availability` 参数行,并调整 `SkillListQuery` 构造:

```java
@GetMapping
public List<SkillListItem> list(
        @RequestParam(name = "view", required = false) String view,
        @RequestParam(name = "sort", required = false) String sort,
        @RequestParam(name = "category", required = false) String category,
        @RequestParam(name = "tag", required = false) String tag,
        @RequestParam(name = "keyword", required = false) String keyword,
        @RequestParam(name = "dimension", required = false) String dimension,
        @RequestParam(name = "limit", required = false) Integer limit,
        @RequestParam(name = "offset", required = false) Integer offset,
        @RequestHeader("X-User-Id") String userId) {
    return skillService.list(new SkillListQuery(view, sort, category, tag, keyword, limit, offset, userId, dimension));
}
```

- [ ] **Step 2: 从 SkillListQuery 移除 availability 字段**

修改 `SkillListQuery.java`,删除 `availability` 字段及其 getter/setter,调整构造函数。具体改法取决于该类的当前结构(是 record 还是 class)。如果是 class,删除 `private String availability;` 字段及对应方法,构造函数移除该参数。

- [ ] **Step 3: 编译验证**

Run: `cd analysis-project ; mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add analysis-project/src/main/java/com/agentscopea2a/v2/controller/SkillController.java analysis-project/src/main/java/com/agentscopea2a/dto/SkillListQuery.java
git commit -m "refactor: remove availability filter parameter"
```

---

### Task 5: 后端 - 新增 tags 与 user-info API

**Files:**
- Modify: `analysis-project/src/main/java/com/agentscopea2a/v2/controller/SkillController.java`
- Modify: `analysis-project/src/main/java/com/agentscopea2a/v2/service/SkillService.java`
- Modify: `analysis-project/src/main/java/com/agentscopea2a/v2/service/MockOrgService.java`
- Modify: `analysis-project/src/main/java/com/agentscopea2a/mapper/mysql/SkillManageMapper.java`
- Modify: `analysis-project/src/main/resources/mybatis/mapper/mysql/SkillManageMapper.xml`
- Create: `analysis-project/src/main/java/com/agentscopea2a/dto/UserInfo.java`

**Interfaces:**
- Produces: `GET /api/skills/tags` 返回 `List<String>`、`GET /api/org/user-info` 返回 `UserInfo`

- [ ] **Step 1: SkillManageMapper 新增 selectAllTags 方法**

在 `SkillManageMapper.java` 接口新增:

```java
List<String> selectAllTags();
```

在 `SkillManageMapper.xml` 新增(在 `</mapper>` 前):

```xml
<select id="selectAllTags" resultType="java.lang.String">
    SELECT DISTINCT tags FROM skill_manage
    WHERE status = 'ACTIVE' AND tags IS NOT NULL AND tags != ''
</select>
```

注意:tags 字段是逗号分隔字符串,前端需自行 split 去重。后端只返回原始 tags 值。

- [ ] **Step 2: 创建 UserInfo DTO**

创建 `analysis-project/src/main/java/com/agentscopea2a/dto/UserInfo.java`:

```java
package com.agentscopea2a.dto;

import java.util.List;

/**
 * 用户信息 DTO(含所属组织),供前端展示测试身份归属。
 */
public record UserInfo(
        String userId,
        List<OrgInfo> orgs
) {
    public record OrgInfo(String orgType, String orgId, String orgName) {}
}
```

- [ ] **Step 3: MockOrgService 新增 getUserInfo 方法**

在 `MockOrgService.java` 新增方法(复用已有的 `getDisplayName` 方法):

```java
public UserInfo getUserInfo(String userId) {
    List<OrgRef> orgRefs = USER_ORGS.getOrDefault(userId, List.of());
    List<UserInfo.OrgInfo> orgs = orgRefs.stream()
            .map(ref -> new UserInfo.OrgInfo(ref.orgType(), ref.orgId(),
                    getDisplayName(ref.orgType(), ref.orgId())))
            .toList();
    return new UserInfo(userId, orgs);
}
```

在 import 区新增:
```java
import com.agentscopea2a.dto.UserInfo;
```

注意:`ORG_REGISTRY` 是 `Map<String, String>`(key="ORG_TYPE:orgId", value=显示名称),已有 `getDisplayName(orgType, orgId)` 方法可直接复用,无需访问内部 map。

- [ ] **Step 4: SkillService 新增 getAllTags 方法**

在 `SkillService.java` 新增:

```java
public List<String> getAllTags() {
    return skillManageMapper.selectAllTags();
}
```

- [ ] **Step 5: SkillController 新增 tags 与 org 端点**

在 `SkillController.java` 新增方法:

```java
@GetMapping("/tags")
public List<String> tags() {
    return skillService.getAllTags();
}
```

新建 `OrgController.java`(或放在现有 controller 中):

```java
package com.agentscopea2a.v2.controller;

import com.agentscopea2a.dto.UserInfo;
import com.agentscopea2a.v2.service.MockOrgService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/org")
@CrossOrigin(origins = "*", maxAge = 3600)
public class OrgController {

    private final MockOrgService mockOrgService;

    public OrgController(MockOrgService mockOrgService) {
        this.mockOrgService = mockOrgService;
    }

    @GetMapping("/user-info")
    public UserInfo userInfo(@RequestParam("userId") String userId) {
        return mockOrgService.getUserInfo(userId);
    }
}
```

- [ ] **Step 6: 编译验证**

Run: `cd analysis-project ; mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 7: Commit**

```bash
git add analysis-project/src/main/java/com/agentscopea2a/
git commit -m "feat: add tags and user-info API endpoints"
```

---

### Task 6: 前端 - types/skill.ts 增加 disabled 字段

**Files:**
- Modify: `analysis-project/frontend/src/types/skill.ts`

**Interfaces:**
- Produces: `SkillListItem` 类型含 `disabled: boolean`,供 Task 7/8 使用

- [ ] **Step 1: 修改 SkillListItem 类型定义**

在 `types/skill.ts` 的 `SkillListItem` 接口中,在 `available` 后新增 `disabled`:

```ts
export interface SkillListItem {
  id: number;
  name: string;
  description: string;
  category: string;
  tags: string;
  ownerUserId: string;
  likeCount: number;
  liked: boolean;
  used: boolean;
  available: boolean;
  disabled: boolean;
  rank?: number;
  updatedAt: string;
  dimension: string;
}
```

- [ ] **Step 2: Commit**

```bash
git add analysis-project/frontend/src/types/skill.ts
git commit -m "feat: add disabled field to frontend SkillListItem type"
```

---

### Task 7: 前端 - SkillCard 状态徽章重构

**Files:**
- Modify: `analysis-project/frontend/src/components/SkillCard.vue:10-22`

**Interfaces:**
- Consumes: `item.disabled`、`item.used`、`item.available`
- Produces: 三态徽章(已使用/已禁用/未使用)

- [ ] **Step 1: 修改 SkillCard 顶部状态行**

将 `SkillCard.vue` 模板的 top 区域(约第 10-13 行)替换为:

```vue
<div class="top">
  <span class="badge" :class="badgeClass" :title="badgeTitle">{{ badgeIcon }}</span>
  <span class="count">👍 {{ item.likeCount }}</span>
</div>
```

并将 `item.dimension === 'PERSONAL'` 的徽章逻辑(约第 18-19 行)扩展为显示所有维度:

```vue
<div class="meta">
  {{ item.ownerUserId }} · {{ item.category || '未分类' }}
  <span class="dim-badge" :class="dimClass">{{ dimLabel }}</span>
</div>
```

- [ ] **Step 2: 修改 script 部分,增加计算属性**

将 `<script setup>` 部分替换为:

```vue
<script setup lang="ts">
import type { SkillListItem } from '../types/skill';
import { computed } from 'vue';

const props = defineProps<{ item: SkillListItem; hideUsed?: boolean }>();
defineEmits<{ (e: 'like'): void }>();

const badgeClass = computed(() => {
  if (props.item.disabled) return 'badge-disabled';
  if (props.item.used) return 'badge-used';
  return 'badge-unused';
});
const badgeIcon = computed(() => {
  if (props.item.disabled) return '🚫';
  if (props.item.used) return '🟢';
  return '⚪';
});
const badgeTitle = computed(() => {
  if (props.item.disabled) return '已禁用';
  if (props.item.used) return '已使用';
  return '未使用';
});

const dimClass = computed(() => `dim-${props.item.dimension.toLowerCase()}`);
const dimLabel = computed(() => {
  const map: Record<string, string> = {
    PERSONAL: '个人', GROUP: '小组', DEPARTMENT: '部门',
    PRODUCT_LINE: '产品线', COMPANY: '公司级',
  };
  return map[props.item.dimension] || '个人';
});
</script>
```

- [ ] **Step 3: 修改 style 部分,增加徽章样式**

在 `<style scoped>` 中替换 `.availability` 并新增徽章样式:

```css
.badge { font-size: 14px; line-height: 1; }
.badge-used { color: #10b981; }
.badge-disabled { color: #ef4444; }
.badge-unused { color: #94a3b8; }

.dim-badge { margin-left: 4px; padding: 1px 8px; border-radius: 4px; font-size: 11px; font-weight: 600; }
.dim-personal { background: #f1f5f9; color: #64748b; }
.dim-group { background: #dbeafe; color: #2563eb; }
.dim-department { background: #d1fae5; color: #047857; }
.dim-product_line { background: #fef3c7; color: #b45309; }
.dim-company { background: #ede9fe; color: #6d28d9; }
```

移除旧的 `.availability` 与 `.dim-badge.personal` 样式。

- [ ] **Step 4: Commit**

```bash
git add analysis-project/frontend/src/components/SkillCard.vue
git commit -m "feat: refactor SkillCard badges to show used/disabled/unused states"
```

---

### Task 8: 前端 - SkillRow 补全状态徽章

**Files:**
- Modify: `analysis-project/frontend/src/components/SkillRow.vue`

**Interfaces:**
- Consumes: `item.disabled`、`item.used`
- Produces: 与 SkillCard 一致的三态徽章 + 维度徽章

- [ ] **Step 1: 读取当前 SkillRow.vue 完整内容**

Run: Read `analysis-project/frontend/src/components/SkillRow.vue`

- [ ] **Step 2: 修改 SkillRow 模板,在名称后增加状态徽章**

在名称后、点赞数前插入状态徽章与维度徽章。参考 SkillCard 的逻辑,在列表行中显示:

```vue
<span class="badge" :class="badgeClass">{{ badgeIcon }}</span>
<span class="dim-badge" :class="dimClass">{{ dimLabel }}</span>
```

- [ ] **Step 3: 修改 script 部分,增加计算属性**

```vue
<script setup lang="ts">
import type { SkillListItem } from '../types/skill';
import { computed } from 'vue';

const props = defineProps<{ item: SkillListItem; hideUsed?: boolean }>();
defineEmits<{ (e: 'like'): void }>();

const badgeClass = computed(() => {
  if (props.item.disabled) return 'badge-disabled';
  if (props.item.used) return 'badge-used';
  return 'badge-unused';
});
const badgeIcon = computed(() => {
  if (props.item.disabled) return '🚫';
  if (props.item.used) return '🟢';
  return '⚪';
});
const dimClass = computed(() => `dim-${props.item.dimension.toLowerCase()}`);
const dimLabel = computed(() => {
  const map: Record<string, string> = {
    PERSONAL: '个人', GROUP: '小组', DEPARTMENT: '部门',
    PRODUCT_LINE: '产品线', COMPANY: '公司级',
  };
  return map[props.item.dimension] || '个人';
});
</script>
```

- [ ] **Step 4: 新增徽章样式**

在 `<style scoped>` 中增加(与 SkillCard 一致):

```css
.badge { font-size: 14px; line-height: 1; margin: 0 4px; }
.badge-used { color: #10b981; }
.badge-disabled { color: #ef4444; }
.badge-unused { color: #94a3b8; }

.dim-badge { padding: 1px 8px; border-radius: 4px; font-size: 11px; font-weight: 600; }
.dim-personal { background: #f1f5f9; color: #64748b; }
.dim-group { background: #dbeafe; color: #2563eb; }
.dim-department { background: #d1fae5; color: #047857; }
.dim-product_line { background: #fef3c7; color: #b45309; }
.dim-company { background: #ede9fe; color: #6d28d9; }
```

- [ ] **Step 5: Commit**

```bash
git add analysis-project/frontend/src/components/SkillRow.vue
git commit -m "feat: add status and dimension badges to SkillRow"
```

---

### Task 9: 前端 - SkillListPage 移除可用性筛选、补全维度与标签

**Files:**
- Modify: `analysis-project/frontend/src/pages/skill/SkillListPage.vue`

**Interfaces:**
- Consumes: `listSkills` API(移除 availability 参数,新增 tag 参数)
- Produces: 维度下拉 5 选项、标签下拉、移除可用性下拉

- [ ] **Step 1: 修改 script 部分**

将 `SkillListPage.vue` 的 `<script setup>` 替换为:

```vue
<script setup lang="ts">
import { ref, watch, computed } from 'vue';
import SkillList from '../../components/SkillList.vue';
import { listSkills, getTags } from '../../api/skill';
import type { SkillListItem } from '../../types/skill';

const props = withDefaults(defineProps<{
  view: 'all' | 'used' | 'liked' | 'created' | 'popular';
  showRank?: boolean;
  allowCategory?: boolean;
}>(), { showRank: false, allowCategory: false });

const items = ref<SkillListItem[]>([]);
const sort = ref<'likes' | 'updated' | 'name'>('likes');
const category = ref('');
const tag = ref('');
const keyword = ref('');
const dimension = ref('');
const categories = ['数据', '办公', '研发', '业务'];
const tagOptions = ref<string[]>([]);
const dimensions = [
  { value: 'PERSONAL', label: '个人' },
  { value: 'GROUP', label: '小组' },
  { value: 'DEPARTMENT', label: '部门' },
  { value: 'PRODUCT_LINE', label: '产品线' },
  { value: 'COMPANY', label: '公司级' },
];

const title = computed(() => ({
  all: '全部 Skill', used: '我使用的 Skill', liked: '我点赞的 Skill',
  created: '我创建的 Skill', popular: '热门榜',
}[props.view]));

const emptyHint = computed(() => ({
  all: '暂无 Skill',
  used: '浏览全部 Skill,引用你需要的',
  liked: '去全部 Skill 找找感兴趣的',
  created: '创建你的第一个 Skill',
  popular: '暂无热门 Skill',
}[props.view]));

async function loadTags() {
  try {
    const rawTags = await getTags();
    // tags 字段是逗号分隔,需 split 去重
    const set = new Set<string>();
    rawTags.forEach(t => t.split(',').forEach(s => {
      const trimmed = s.trim();
      if (trimmed) set.add(trimmed);
    }));
    tagOptions.value = Array.from(set).sort();
  } catch {
    tagOptions.value = [];
  }
}

async function load() {
  items.value = await listSkills({
    view: props.view, sort: sort.value,
    category: category.value || undefined,
    tag: tag.value || undefined,
    keyword: keyword.value || undefined,
    dimension: dimension.value || undefined,
  });
}
watch([sort, category, tag, dimension, () => props.view], load, { immediate: true });
loadTags();
</script>
```

- [ ] **Step 2: 修改 template 部分**

将 `<template>` 替换为:

```vue
<template>
  <h2>{{ title }}</h2>
  <div class="bar">
    <input v-model="keyword" placeholder="搜索 skill" @keyup.enter="load" />
    <select v-if="allowCategory" v-model="category">
      <option value="">全部分类</option>
      <option v-for="c in categories" :key="c" :value="c">{{ c }}</option>
    </select>
    <select v-model="tag">
      <option value="">全部标签</option>
      <option v-for="t in tagOptions" :key="t" :value="t">{{ t }}</option>
    </select>
    <select v-model="dimension">
      <option value="">全部维度</option>
      <option v-for="d in dimensions" :key="d.value" :value="d.value">{{ d.label }}</option>
    </select>
    <select v-model="sort">
      <option value="likes">点赞最多</option>
      <option value="updated">最新更新</option>
      <option value="name">名称</option>
    </select>
    <RouterLink v-if="view === 'created'" class="create" to="/skills/new">＋ 创建 Skill</RouterLink>
  </div>
  <SkillList :items="items" :show-rank="showRank" :hide-used="view === 'used'" />
  <div v-if="items.length === 0" class="empty-hint">{{ emptyHint }}</div>
</template>
```

- [ ] **Step 3: 新增空状态样式**

在 `<style scoped>` 中新增:

```css
.empty-hint { color: #94a3b8; font-size: 14px; text-align: center; padding: 32px 0; }
```

- [ ] **Step 4: Commit**

```bash
git add analysis-project/frontend/src/pages/skill/SkillListPage.vue
git commit -m "feat: remove availability filter, add tag and dimension filters, add empty hints"
```

---

### Task 10: 前端 - api/skill.ts 调整

**Files:**
- Modify: `analysis-project/frontend/src/api/skill.ts`

**Interfaces:**
- Produces: `listSkills` 移除 availability、`getTags`、`getUserInfo`、`getReferencers` 函数

- [ ] **Step 1: 修改 listSkills 参数类型**

移除 `availability` 参数,确保 `tag` 参数存在:

```ts
export interface ListSkillParams {
  view?: 'all' | 'used' | 'liked' | 'created' | 'popular';
  sort?: 'likes' | 'updated' | 'name';
  category?: string;
  tag?: string;
  keyword?: string;
  dimension?: string;
  limit?: number;
  offset?: number;
}

export async function listSkills(params: ListSkillParams = {}): Promise<SkillListItem[]> {
  const query: Record<string, string> = {};
  Object.entries(params).forEach(([k, v]) => {
    if (v != null && v !== '') query[k] = String(v);
  });
  const res = await fetch(`${BASE}/api/skills?${new URLSearchParams(query)}`, { headers: authHeaders() });
  if (!res.ok) throw new Error(`listSkills ${res.status}`);
  return res.json();
}
```

- [ ] **Step 2: 新增 getTags、getUserInfo、getReferencers 函数**

```ts
export async function getTags(): Promise<string[]> {
  const res = await fetch(`${BASE}/api/skills/tags`, { headers: authHeaders() });
  if (!res.ok) throw new Error(`getTags ${res.status}`);
  return res.json();
}

export interface OrgInfo { orgType: string; orgId: string; orgName: string; }
export interface UserInfo { userId: string; orgs: OrgInfo[]; }

export async function getUserInfo(userId: string): Promise<UserInfo> {
  const res = await fetch(`${BASE}/api/org/user-info?userId=${encodeURIComponent(userId)}`, { headers: authHeaders() });
  if (!res.ok) throw new Error(`getUserInfo ${res.status}`);
  return res.json();
}

export async function getReferencers(skillId: number): Promise<string[]> {
  const res = await fetch(`${BASE}/api/skills/${skillId}/referencers`, { headers: authHeaders() });
  if (!res.ok) throw new Error(`getReferencers ${res.status}`);
  return res.json();
}
```

- [ ] **Step 3: Commit**

```bash
git add analysis-project/frontend/src/api/skill.ts
git commit -m "feat: update skill API - remove availability, add tags/userInfo/referencers"
```

---

### Task 11: 前端 - SkillShell 身份切换器优化

**Files:**
- Modify: `analysis-project/frontend/src/components/SkillShell.vue`

**Interfaces:**
- Consumes: `getUserInfo` API
- Produces: 顶栏头像下拉、显示组织信息、测试身份标识

- [ ] **Step 1: 修改 script 部分,加载用户信息**

```vue
<script setup lang="ts">
import { ref, computed, onMounted } from 'vue';
import { RouterLink, RouterView, useRouter } from 'vue-router';
import { currentUserId, getUserInfo } from '../api/skill';
import type { UserInfo } from '../api/skill';

const USERS = [
  { id: 'user_001',     label: '张三(user_001)' },
  { id: 'user_002',     label: '李四(user_002)' },
  { id: 'user_003',     label: '王五(user_003)' },
  { id: 'approver_001', label: '审批人A(approver_001)' },
  { id: 'approver_002', label: '审批人B(approver_002)' },
  { id: 'approver_003', label: '审批人C(approver_003)' },
  { id: 'demo-user',    label: '访客(demo-user)' },
];

const router = useRouter();
const current = ref(currentUserId());
const userInfo = ref<UserInfo | null>(null);
const showDropdown = ref(false);

const currentLabel = computed(() => {
  const u = USERS.find(u => u.id === current.value);
  return u ? u.label : current.value;
});

const orgSummary = computed(() => {
  if (!userInfo.value || userInfo.value.orgs.length === 0) return '';
  return userInfo.value.orgs.map(o => o.orgName).join('/');
});

async function loadUserInfo() {
  try {
    userInfo.value = await getUserInfo(current.value);
  } catch {
    userInfo.value = null;
  }
}

function switchUser(id: string) {
  localStorage.setItem('skill-user-id', id);
  current.value = id;
  showDropdown.value = false;
  router.go(0);
}

function toggleDropdown() {
  showDropdown.value = !showDropdown.value;
}

onMounted(loadUserInfo);

const nav = [
  { to: '/skills', label: '全部 Skill' },
  { to: '/skills/used', label: '我使用的' },
  { to: '/skills/liked', label: '我点赞的' },
  { to: '/skills/created', label: '我创建的' },
  { to: '/skills/popular', label: '热门榜' },
  { to: '/skills/category', label: '分类浏览' },
  { to: '/skills/approvals', label: '待我审批' },
];
</script>
```

- [ ] **Step 2: 修改 template,将切换器移到顶栏头像位置**

```vue
<template>
  <div class="skill-shell">
    <aside class="nav">
      <div class="logo">Skill 广场</div>
      <RouterLink v-for="n in nav" :key="n.to" :to="n.to" class="nav-item">{{ n.label }}</RouterLink>
    </aside>
    <main class="content">
      <div class="topbar">
        <div class="topbar-left">
          <span class="test-tag">测试身份</span>
        </div>
        <div class="user-area" @click="toggleDropdown">
          <span class="user-name">{{ currentLabel }}</span>
          <span v-if="orgSummary" class="user-org">{{ orgSummary }}</span>
          <span class="user-avatar">👤</span>
          <div v-if="showDropdown" class="user-dropdown">
            <div v-for="u in USERS" :key="u.id" class="dropdown-item"
                 :class="{ active: u.id === current }"
                 @click.stop="switchUser(u.id)">
              {{ u.label }}
            </div>
          </div>
        </div>
      </div>
      <RouterView />
    </main>
  </div>
</template>
```

- [ ] **Step 3: 修改 style**

```vue
<style scoped>
.skill-shell { display: flex; min-height: 100vh; }
.nav { width: 200px; background: #0f172a; color: #cbd5e1; padding: 12px; display: flex; flex-direction: column; gap: 4px; }
.logo { font-weight: bold; color: #fff; margin-bottom: 12px; }
.nav-item { padding: 8px 10px; border-radius: 6px; text-decoration: none; color: #cbd5e1; }
.nav-item.router-link-active { background: #3b82f6; color: #fff; }
.content { flex: 1; padding: 16px; background: #f1f5f9; }
.topbar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; padding: 8px 12px; background: #fff; border-radius: 8px; box-shadow: 0 1px 3px rgba(0,0,0,0.06); }
.test-tag { font-size: 11px; color: #f59e0b; background: #fef3c7; padding: 2px 8px; border-radius: 4px; font-weight: 600; }
.user-area { position: relative; cursor: pointer; display: flex; align-items: center; gap: 8px; padding: 4px 8px; border-radius: 6px; }
.user-area:hover { background: #f1f5f9; }
.user-name { font-size: 13px; color: #1e293b; font-weight: 500; }
.user-org { font-size: 11px; color: #64748b; }
.user-avatar { font-size: 18px; }
.user-dropdown { position: absolute; top: 100%; right: 0; background: #fff; border: 1px solid #e2e8f0; border-radius: 8px; box-shadow: 0 4px 12px rgba(0,0,0,0.1); min-width: 200px; z-index: 100; margin-top: 4px; }
.dropdown-item { padding: 8px 12px; font-size: 13px; cursor: pointer; color: #1e293b; }
.dropdown-item:hover { background: #f1f5f9; }
.dropdown-item.active { background: #dbeafe; color: #2563eb; font-weight: 600; }
</style>
```

- [ ] **Step 4: Commit**

```bash
git add analysis-project/frontend/src/components/SkillShell.vue
git commit -m "feat: move user switcher to topbar with org info and test identity tag"
```

---

### Task 12: 前端 - SkillDetailPage 展示被引用人数

**Files:**
- Modify: `analysis-project/frontend/src/pages/skill/SkillDetailPage.vue`

**Interfaces:**
- Consumes: `getReferencers` API

- [ ] **Step 1: 在 script 中加载被引用人数**

在 `SkillDetailPage.vue` 的 `<script setup>` 中新增:

```ts
import { getReferencers } from '../../api/skill';

const referencerCount = ref(0);
const referencersLoading = ref(false);

async function loadReferencers() {
  if (!skill.value) return;
  referencersLoading.value = true;
  try {
    const list = await getReferencers(skill.value.id);
    referencerCount.value = list.length;
  } catch {
    referencerCount.value = 0;
  } finally {
    referencersLoading.value = false;
  }
}
```

在 `loadSkill` 成功后调用 `loadReferencers()`。

- [ ] **Step 2: 在 actions 区展示被引用人数**

在 `actions` div(约第 402 行)内,引用按钮后新增:

```vue
<span class="referencer-count">被 {{ referencerCount }} 人引用</span>
```

- [ ] **Step 3: 新增样式**

```css
.referencer-count { font-size: 12px; color: #64748b; padding: 4px 8px; }
```

- [ ] **Step 4: Commit**

```bash
git add analysis-project/frontend/src/pages/skill/SkillDetailPage.vue
git commit -m "feat: show referencer count on skill detail page"
```

---

### Task 13: 验证 - 构建与端到端验证

**Files:** 无修改

- [ ] **Step 1: 后端编译**

Run: `cd analysis-project ; mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 2: 前端构建**

Run: `cd analysis-project/frontend ; npm run build`
Expected: 构建成功无错误

- [ ] **Step 3: 启动应用手动验证**

启动后端服务,打开前端页面,验证以下场景:

1. **身份切换**:顶栏头像下拉显示当前用户+组织信息,切换后刷新正确
2. **创建并引用 Skill**:创建 Skill -> 引用 -> 列表显示 🟢已使用
3. **禁用 Skill**:禁用已引用 Skill -> 列表显示 🚫已禁用
4. **移除可用性筛选**:筛选条无"可用性"下拉
5. **维度筛选**:维度下拉有 5 个选项(个人/小组/部门/产品线/公司级)
6. **标签筛选**:标签下拉显示已有标签
7. **SkillRow 徽章**:列表视图显示状态徽章与维度徽章
8. **被引用人数**:详情页显示"被 N 人引用"
9. **空状态引导**:各视图空时有引导文案

- [ ] **Step 4: 最终 Commit(如有修复)**

```bash
git add -A
git commit -m "fix: address issues found during e2e verification"
```
