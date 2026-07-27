# Skill 维度展示与切换方案

## 总结

在现有 `skill_publish` 发布关系基础上,前端详情页新增"维度"展示区,后端新增查询 Skill 发布目标的 API,前端新增"申请发布到维度"的入口(复用现有审批流)。不新增数据库字段,维度从 `skill_publish` 记录派生。

## 现状分析

- **Skill 实体无维度字段**:`skill_manage` 表只有 `owner_user_id`、`status` 等,无 `visibility`/`scope`
- **维度隐式存在于 `skill_publish`**:`target_type`(GROUP/DEPARTMENT/PRODUCT_LINE/COMPANY)+ `target_id` + `status`(PENDING/APPROVED/REJECTED)
- **创建时默认个人维度**:无任何 APPROVED 发布记录 = 个人维度
- **后端已有完整发布审批流**:`SkillPublishService.submitPublish` / `approve` / `reject` + `SkillPublishController` 全套接口
- **前端缺失**:`api/skill.ts` 未封装 `submitPublish` 调用;详情页无维度展示;无"申请发布"入口

## 维度定义与组织注册表(前置设计)

### 维度类型与展示格式

每个维度展示时**必须带具体组织名**,格式:`维度类型:具体组织名`。

| 维度类型 target_type | 展示前缀 | 示例展示 | 含义 |
|---------------------|---------|---------|------|
| (无发布记录) | 个人 | `个人` | 仅 owner 可见可用 |
| GROUP | 小组 | `小组:开发一组` | 该小组成员可用 |
| DEPARTMENT | 部门 | `部门:研发部` | 该部门成员可用 |
| PRODUCT_LINE | 产品线 | `产品线:数据产品线` | 该产品线成员可用 |
| COMPANY | 公司级 | `杭研` | 全公司可用(杭研 = 杭州研发) |

**多组织场景**:一个 Skill 同时发布到"开发一组"和"研发部",展示为 `小组:开发一组、部门:研发部`。

### 组织注册表设计(MockOrgService 扩展)

当前 MockOrgService 仅有 2 个 GROUP + 1 个 DEPARTMENT,缺 PRODUCT_LINE、COMPANY,且无显示名称。需补全为完整的四级组织注册表:

```java
// 组织注册表:key = "ORG_TYPE:orgId",value = 显示名称
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

// 组织级别(用于展示前缀)
private static final Map<String, String> ORG_TYPE_LABEL = Map.of(
    "GROUP",        "小组",
    "DEPARTMENT",   "部门",
    "PRODUCT_LINE", "产品线",
    "COMPANY",      "杭研"
);
```

### 审批人配置(同步扩展)

```java
// org -> approver 模拟映射
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
```

### 用户归属扩展

```java
// user -> orgs 模拟映射(扩展更多用户与组织)
private static final Map<String, List<OrgRef>> USER_ORGS = Map.of(
    "user_001",     List.of(new OrgRef("GROUP", "group_001"), new OrgRef("GROUP", "group_002")),
    "user_002",     List.of(new OrgRef("GROUP", "group_001")),
    "user_003",     List.of(new OrgRef("GROUP", "group_003")),               // 统计组用户
    "approver_001", List.of(new OrgRef("GROUP", "group_001")),
    "approver_002", List.of(new OrgRef("GROUP", "group_003")),               // 统计组审批人
    "approver_003", List.of(new OrgRef("DEPARTMENT", "dept_001")),           // 部门/产品线/公司级审批人
    "demo-user",    List.of(new OrgRef("GROUP", "group_001"))
);
```

### 新增 MockOrgService 方法

```java
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
```

### 展示规则总结

**详情页维度标签**(`dimensionLabel` computed):

- 无 APPROVED 记录 -> `个人`
- 有 APPROVED 记录 -> 逐条调用 `getFullDimensionLabel`,用 `、` 连接
  - 示例:`小组:开发一组、部门:研发部`
- COMPANY 类型特殊处理:直接显示 `杭研`(不加"公司级:"前缀)

**发布申请弹窗下拉**:

- 每个选项显示完整维度文本:`小组:统计组` / `产品线:数据产品线` / `杭研`
- 用户只看到自己所属的组织(`getUserOrgs` 返回的列表)

**发布记录列表**:

- 每条记录显示 `targetName`(已存储在 `skill_publish.target_name` 字段)
- 状态 badge:`APPROVED`(绿) / `PENDING`(黄) / `REJECTED`(红)

> **关键**:`skill_publish.target_name` 在提交发布时即写入(由 `getDisplayName` 提供),后续展示直接读取,无需再次查注册表。但详情页维度标签需要从 `publishes` 列表重新格式化(加类型前缀),所以前端需要知道 `targetType` 来拼前缀--后端返回的 `SkillPublish` 实体已包含 `targetType` 字段。

## 改动清单

### 1. 后端:新增查询 Skill 发布记录的 API

**文件**: [SkillPublishController.java](file:///d:/deng/feature_aigent_data/analysis-project/src/main/java/com/agentscopea2a/v2/controller/SkillPublishController.java)

新增 `GET /api/skills/{id}/publishes` 接口,返回指定 Skill 的全部发布记录(含 APPROVED 和 PENDING):

```java
@GetMapping("/skills/{id}/publishes")
public List<SkillPublish> publishes(@PathVariable Long id) {
    return service.listBySkillId(id);
}
```

**文件**: [SkillPublishService.java](file:///d:/deng/feature_aigent_data/analysis-project/src/main/java/com/agentscopea2a/v2/service/SkillPublishService.java)

新增 `listBySkillId` 方法,复用已有的 `mapper.selectBySkillId(skillId)`(XML 第 42-44 行已实现,按 created_at DESC 排序):

```java
public List<SkillPublish> listBySkillId(Long skillId) {
    return mapper.selectBySkillId(skillId);
}
```

### 2. 后端:扩展 MockOrgService 组织注册表

**文件**: [MockOrgService.java](file:///d:/deng/feature_aigent_data/analysis-project/src/main/java/com/agentscopea2a/v2/service/MockOrgService.java)

按"组织注册表设计"章节所述,替换现有 `USER_ORGS`、`ORG_APPROVER` 两个 Map,新增 `ORG_REGISTRY`、`ORG_TYPE_LABEL` 两个 Map,并新增 `getDisplayName`、`getTypeLabel`、`getFullDimensionLabel` 三个方法。

同时在 `APPROVER_USER_IDS` 中加入 `approver_002`:

```java
private static final Set<String> APPROVER_USER_IDS = Set.of("approver_001", "approver_002", "approver_003");
```

### 3. 后端:新增查询当前用户可选组织目标的 API

**文件**: [SkillPublishController.java](file:///d:/deng/feature_aigent_data/analysis-project/src/main/java/com/agentscopea2a/v2/controller/SkillPublishController.java)

新增 `GET /api/skills/publish-targets` 接口,返回当前用户所属的组织列表(含完整维度标签):

```java
@GetMapping("/skills/publish-targets")
public List<OrgTarget> publishTargets(@RequestHeader("X-User-Id") String userId) {
    return mockOrgService.getUserOrgs(userId).stream()
        .map(o -> new OrgTarget(o.orgType(), o.orgId(),
                mockOrgService.getDisplayName(o.orgType(), o.orgId()),
                mockOrgService.getFullDimensionLabel(o.orgType(), o.orgId())))
        .toList();
}

/** 发布目标选项。 */
public record OrgTarget(String orgType, String orgId, String displayName, String fullLabel) {}
```

需要在 `SkillPublishController` 构造函数中注入 `MockOrgService`(当前未注入)。

### 3. 前端:api/skill.ts 新增封装

**文件**: [skill.ts](file:///d:/deng/feature_aigent_data/analysis-project/frontend/src/api/skill.ts)

新增三个函数:

```typescript
/** 查询 Skill 的发布记录列表(GET /api/skills/{id}/publishes)。 */
export async function getSkillPublishes(id: number): Promise<SkillPublishRecord[]> {
  const res = await fetch(`${BASE}/${id}/publishes`, { headers: authHeaders() });
  if (!res.ok) throw await skillError(res, '获取发布记录失败');
  return res.json();
}

/** 查询当前用户可选的发布目标(GET /api/skills/publish-targets)。 */
export async function getPublishTargets(): Promise<PublishTarget[]> {
  const res = await fetch(`${BASE}/publish-targets`, { headers: authHeaders() });
  if (!res.ok) throw await skillError(res, '获取发布目标失败');
  return res.json();
}

/** 申请发布 Skill(POST /api/skills/{id}/publish)。 */
export async function submitPublish(id: number, targetType: string, targetId: string, targetName: string): Promise<number> {
  const res = await fetch(`${BASE}/${id}/publish`, {
    method: 'POST',
    headers: { ...authHeaders(), 'Content-Type': 'application/json' },
    body: JSON.stringify({ targetType, targetId, targetName }),
  });
  if (!res.ok) throw await skillError(res, '申请发布失败');
  const data = await res.json();
  return data.publishId;
}
```

### 4. 前端:types/skill.ts 新增类型

**文件**: [skill.ts](file:///d:/deng/feature_aigent_data/analysis-project/frontend/src/types/skill.ts)

```typescript
/** Skill 发布记录。对应 GET /api/skills/{id}/publishes 的单条。 */
export interface SkillPublishRecord {
  id: number;
  skillId: number;
  targetType: string;   // GROUP / DEPARTMENT / PRODUCT_LINE / COMPANY
  targetId: string;
  targetName: string;   // 组织显示名称(如"开发一组"、"杭研")
  status: string;       // PENDING / APPROVED / REJECTED
  submitter: string;
  approver: string | null;
  approveTime: string | null;
  currentApproverUserId: string | null;
  lastApprovalComment: string | null;
  lastApprovalAt: string | null;
  createdAt: string;
}

/** 可选的发布目标。对应 GET /api/skills/publish-targets 的单条。 */
export interface PublishTarget {
  orgType: string;
  orgId: string;
  displayName: string;   // 组织名称(如"开发一组")
  fullLabel: string;     // 完整维度标签(如"小组:开发一组"、"杭研")
}
```

### 5. 前端:详情页 SkillDetailPage.vue 新增维度展示区

**文件**: [SkillDetailPage.vue](file:///d:/deng/feature_aigent_data/analysis-project/frontend/src/pages/skill/SkillDetailPage.vue)

#### 5.1 script 区新增

```typescript
import { getSkillPublishes, getPublishTargets, submitPublish } from '../../api/skill';
import type { SkillPublishRecord, PublishTarget } from '../../types/skill';

// 维度/发布
const publishes = ref<SkillPublishRecord[]>([]);
const publishLoading = ref(false);
const publishError = ref('');
const showPublishDialog = ref(false);
const publishTargets = ref<PublishTarget[]>([]);
const selectedTarget = ref('');
const submitting = ref(false);

// 维度类型 -> 前缀(COMPANY 特殊处理为空前缀,直接显示 targetName)
const TYPE_LABEL: Record<string, string> = {
  GROUP: '小组',
  DEPARTMENT: '部门',
  PRODUCT_LINE: '产品线',
  COMPANY: '',           // 杭研直接显示,不加前缀
};

// 拼接维度展示文本(如"小组:开发一组"、"杭研")
function formatDimension(p: SkillPublishRecord): string {
  const label = TYPE_LABEL[p.targetType] ?? '';
  return label ? `${label}:${p.targetName}` : p.targetName;
}

// 派生:当前维度标签(取已审批通过的记录)
const dimensionLabel = computed(() => {
  const approved = publishes.value.filter(p => p.status === 'APPROVED');
  if (approved.length === 0) return '个人';
  return approved.map(formatDimension).join('、');
});

// 派生:是否有审批中的申请
const hasPendingPublish = computed(() =>
  publishes.value.some(p => p.status === 'PENDING')
);
```

在 `load()` 函数中加入加载发布记录(失败不阻塞主流程):

```typescript
loadPublishes(id);
```

```typescript
async function loadPublishes(id: number) {
  publishLoading.value = true;
  publishError.value = '';
  try {
    publishes.value = await getSkillPublishes(id);
  } catch (e) {
    publishError.value = e instanceof Error ? e.message : '获取维度信息失败';
  } finally {
    publishLoading.value = false;
  }
}

async function openPublishDialog() {
  showPublishDialog.value = true;
  if (publishTargets.value.length === 0) {
    try {
      publishTargets.value = await getPublishTargets();
    } catch (e) {
      publishError.value = e instanceof Error ? e.message : '获取发布目标失败';
    }
  }
}

async function doSubmitPublish() {
  if (!skill.value || submitting.value || !selectedTarget.value) return;
  const target = publishTargets.value.find(t => `${t.orgType}:${t.orgId}` === selectedTarget.value);
  if (!target) return;
  submitting.value = true;
  publishError.value = '';
  try {
    await submitPublish(skill.value.id, target.orgType, target.orgId, target.fullLabel);
    showPublishDialog.value = false;
    selectedTarget.value = '';
    await loadPublishes(skill.value.id);
  } catch (e) {
    publishError.value = e instanceof Error ? e.message : '申请发布失败';
  } finally {
    submitting.value = false;
  }
}
```

#### 5.2 template 区新增(在 meta 行下方、manage 区之前)

```html
<!-- 维度展示区 -->
<div class="dimension-bar">
  <span class="dim-label">维度:</span>
  <span class="dim-value">{{ publishLoading ? '加载中…' : dimensionLabel }}</span>
  <span v-if="hasPendingPublish" class="dim-pending">审批中</span>
  <button v-if="canManage" class="dim-change" @click="openPublishDialog">申请发布到其他维度</button>
</div>

<!-- 发布记录列表(折叠) -->
<details v-if="publishes.length > 0" class="publish-list">
  <summary>发布记录 ({{ publishes.length }})</summary>
  <ul>
    <li v-for="p in publishes" :key="p.id" class="publish-item">
      <span class="p-target">{{ p.targetName }}</span>
      <span class="p-status" :class="p.status.toLowerCase()">{{ p.status }}</span>
      <span class="p-meta">提交人 {{ p.submitter }}</span>
      <span v-if="p.approver" class="p-meta">审批人 {{ p.approver }}</span>
    </li>
  </ul>
</details>

<!-- 发布申请弹窗 -->
<div v-if="showPublishDialog" class="dialog-overlay" @click.self="showPublishDialog = false">
  <div class="dialog">
    <h3>申请发布到维度</h3>
    <p class="dialog-tip">选择目标组织,提交后需该组织审批人审批通过后生效。</p>
    <select v-model="selectedTarget">
      <option value="">请选择目标组织</option>
      <option v-for="t in publishTargets" :key="t.orgId" :value="`${t.orgType}:${t.orgId}`">
        {{ t.fullLabel }}
      </option>
    </select>
    <div class="dialog-actions">
      <button @click="showPublishDialog = false">取消</button>
      <button :disabled="submitting || !selectedTarget" @click="doSubmitPublish">
        {{ submitting ? '提交中…' : '提交申请' }}
      </button>
    </div>
  </div>
</div>
```

#### 5.3 style 区新增

```css
.dimension-bar { display: flex; align-items: center; gap: 8px; margin-bottom: 8px; font-size: 13px; }
.dim-label { color: #94a3b8; }
.dim-value { font-weight: 600; color: #475569; }
.dim-pending { background: #fef3c7; color: #b45309; padding: 0 6px; border-radius: 4px; font-size: 11px; }
.dim-change { margin-left: auto; padding: 2px 10px; border-radius: 6px; border: 1px solid #93c5fd; background: #eff6ff; color: #2563eb; cursor: pointer; font-size: 12px; }
.dim-change:hover { background: #dbeafe; }
.publish-list { margin-bottom: 12px; font-size: 13px; }
.publish-list summary { cursor: pointer; color: #64748b; }
.publish-item { display: flex; gap: 8px; padding: 4px 0; align-items: center; }
.p-target { font-weight: 500; }
.p-status { padding: 0 6px; border-radius: 4px; font-size: 11px; }
.p-status.approved { background: #d1fae5; color: #047857; }
.p-status.pending { background: #fef3c7; color: #b45309; }
.p-status.rejected { background: #fee2e2; color: #b91c1c; }
.p-meta { color: #94a3b8; font-size: 12px; }
.dialog-overlay { position: fixed; inset: 0; background: rgba(0,0,0,0.4); display: flex; align-items: center; justify-content: center; z-index: 100; }
.dialog { background: #fff; border-radius: 8px; padding: 20px; width: 400px; box-shadow: 0 4px 12px rgba(0,0,0,0.15); }
.dialog h3 { margin: 0 0 8px; }
.dialog-tip { color: #64748b; font-size: 13px; margin: 0 0 12px; }
.dialog select { width: 100%; padding: 8px; border: 1px solid #cbd5e1; border-radius: 6px; margin-bottom: 12px; }
.dialog-actions { display: flex; justify-content: flex-end; gap: 8px; }
.dialog-actions button { padding: 6px 16px; border-radius: 6px; cursor: pointer; }
.dialog-actions button:first-child { border: 1px solid #cbd5e1; background: #fff; }
.dialog-actions button:last-child { background: #3b82f6; color: #fff; border: none; }
.dialog-actions button:last-child:disabled { opacity: 0.5; cursor: not-allowed; }
```

### 6. 前端:列表卡片维度 badge(可选,轻量增强)

**文件**: [SkillCard.vue](file:///d:/deng/feature_aigent_data/analysis-project/frontend/src/components/SkillCard.vue)

在 meta 行后新增维度 badge。由于 `SkillListItem` 目前不含维度信息,有两种方式:

**方式 A(推荐,零后端改动)**: 卡片不显示维度 badge,仅详情页展示维度。理由:列表卡片已信息密集(可用性、点赞、标签),再加维度 badge 会过载;维度是详情级信息,用户点进去看即可。

**方式 B(需后端改动)**: 在 `SkillListItem` record 新增 `dimension` 字段,`SkillService.list` 中从 `approved` 发布记录派生维度标签注入。改动后端 DTO + list 逻辑。

本计划采用**方式 A**:卡片不显示维度,仅详情页展示。

## 展示效果示意

详情页(个人维度):
```
SQL 查询优化  👍 128
demo-user · 数据分析 · 状态 ACTIVE
维度: 个人                                [申请发布到其他维度]
✎ 编辑  🗑 删除
```

详情页(已发布到小组 + 申请发布到部门,审批中):
```
SQL 查询优化  👍 128
demo-user · 数据分析 · 状态 ACTIVE
维度: 小组:开发一组  审批中                [申请发布到其他维度]
✎ 编辑  🗑 删除

▸ 发布记录 (2)
  小组:开发一组  APPROVED  提交人 demo-user  审批人 approver_001
  部门:研发部    PENDING   提交人 demo-user
```

详情页(已发布到公司级杭研):
```
SQL 查询优化  👍 128
demo-user · 数据分析 · 状态 ACTIVE
维度: 杭研                                [申请发布到其他维度]
✎ 编辑  🗑 删除
```

详情页(多维度:小组 + 产品线):
```
SQL 查询优化  👍 128
demo-user · 数据分析 · 状态 ACTIVE
维度: 小组:统计组、产品线:数据产品线       [申请发布到其他维度]
✎ 编辑  🗑 删除
```

发布申请弹窗(当前用户 demo-user 属于开发一组):
```
┌─ 申请发布到维度 ──────────────┐
│ 选择目标组织,提交后需该组织    │
│ 审批人审批通过后生效。         │
│                               │
│ [小组:开发一组          ▼]    │
│                               │
│              [取消] [提交申请] │
└───────────────────────────────┘
```

## 假设与决策

1. **维度从 `skill_publish` 派生,不新增字段** - 与设计文档 §2.1 一致("Skill 是内容资产,发布关系决定谁可以使用")
2. **"修改维度"= 提交新的发布申请,走现有审批流** - 用户确认
3. **卡片不显示维度 badge** - 避免信息过载,维度是详情级信息
4. **一个 Skill 可发布到多个组织** - 列出全部已发布维度,而非取"最高级别"
5. **组织注册表硬编码在 MockOrgService** - 与现有 mock 风格一致,后续可迁移到数据库表
6. **维度展示带具体组织名** - 每个维度必须展示具体归属(如"小组:统计组"、"产品线:数据产品线"、"杭研"),不能只显示维度类型
7. **COMPANY 类型特殊处理** - 直接显示"杭研",不加"公司级:"前缀
8. **`target_name` 存储完整维度标签** - 提交发布时 `target_name` 写入 `fullLabel`(如"小组:开发一组"),发布记录列表直接读取展示
9. **发布记录查询失败不阻塞详情页主流程** - 与禁用状态、草稿加载同等处理

## 验证步骤

1. 后端重启后,访问 `GET /api/skills/{id}/publishes` 返回该 Skill 的发布记录列表,每条含 `targetType`、`targetName`
2. 访问 `GET /api/skills/publish-targets`(带 X-User-Id header)返回当前用户所属组织列表,每条含 `fullLabel`(如"小组:开发一组")
3. 详情页个人维度 Skill 显示"维度: 个人" + "申请发布到其他维度"按钮
4. 点击按钮弹出弹窗,下拉显示完整维度标签(如"小组:开发一组"),而非裸 orgId
5. 选择目标并提交,`skill_publish.target_name` 存入"小组:开发一组";详情页出现"审批中"标记 + 发布记录列表
6. 审批人(approver_001)在"待我审批"页面看到该发布申请,通过后详情页维度标签更新为"小组:开发一组"
7. 已发布到统计组的 Skill,详情页显示"维度: 小组:统计组"(验证具体组织名展示)
8. 已发布到杭研(公司级)的 Skill,详情页显示"维度: 杭研"(验证 COMPANY 特殊处理)
9. 已有 APPROVED 发布记录的 Skill,编辑时仍走草稿审批流(现有逻辑不受影响)
