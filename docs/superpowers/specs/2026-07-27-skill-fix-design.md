# Skill 管理平台修复设计文档

- **日期**: 2026-07-27
- **状态**: Draft
- **作者**: brainstorming session
- **关联**: 修复 `2026-07-20-skill-manage-design.md` 中 5 个问题点

***

## 1. 问题背景

基于用户反馈与代码审查,当前 Skill 管理平台存在 5 个问题:

| # | 问题 | 根因 |
|---|------|------|
| 1 | 当前身份是模拟数据,体验奇怪 | SkillShell.vue 硬编码 7 个测试用户,切换器藏在侧边栏不直观 |
| 2 | "可用"筛选返回空 | SkillService.java:86 的 `available` 计算仅判断"是否有 APPROVED 发布记录",不匹配用户组织、不排除禁用;新创建 Skill 未走发布审批,必然 available=false |
| 3 | 列表页不显示"已使用"/"已禁用"状态 | SkillCard/SkillRow 未展示禁用状态;SkillRow 甚至缺可用性徽章 |
| 4 | 希望移除可用性筛选,引用即可用 | 原设计"发布到组织+未禁用=可用"过于复杂,与用户直觉不符 |
| 5 | 维度数据不全 | MockOrgService 中所有用户只归属 GROUP,无用户归属 DEPARTMENT/PRODUCT_LINE/COMPANY;前端维度下拉只有"个人" |

***

## 2. 修复方案(方案 A: 统一修复)

一次性修复全部 5 个问题,保持逻辑一致性。因为"引用即可用"会影响后端 `available` 计算、前端筛选、卡片徽章等多个关联点,分散修复会导致中间状态不一致。

***

## 3. 详细设计

### 3.1 可用性模型重构(问题 2、4)

#### 3.1.1 核心变更:重新定义"可用"

将"可用"从"发布到组织+未禁用"改为**"引用即可用"**。

**新模型**:

```
Skill 可用 = 用户已引用该 Skill (skill_reference 存在记录)
             AND 未个人禁用 (skill_user_disable 无记录)
```

#### 3.1.2 后端改动

**文件**: `SkillService.java`

**`available` 字段计算改为**:

1. 查 `skill_reference WHERE creator=userId AND target_skill_id IN (当前页 skill_ids)` 得已引用集合
2. 查 `skill_user_disable WHERE user_id=userId AND skill_id IN (...)` 得已禁用集合
3. `available = used && !disabled`

**移除对 `skill_publish` 的依赖**: 发布审批不再影响可用性计算。

**移除 `availability` 筛选参数**: 语义已被"我使用的"视图(`view=used`)覆盖,避免冗余。

#### 3.1.3 前端改动

**文件**: `SkillListPage.vue`

- 移除"可用性"下拉筛选

**文件**: `SkillCard.vue` / `SkillRow.vue`

- 卡片徽章语义变更:🟢 已引用可用 / ⚪ 未引用 / 🚫 已禁用

#### 3.1.4 发布审批功能保留

发布审批仍存在,但语义变为"推广到组织维度"(可选操作),不再影响个人可用性。作者可选地将 Skill 推广到某维度,卡片上展示推广范围徽章。

### 3.2 列表页状态徽章(问题 3)

#### 3.2.1 状态徽章规则

列表页展示三个状态徽章:

| used | disabled | 显示 |
|------|----------|------|
| true | false | 🟢 已使用 |
| true | true | 🚫 已禁用 |
| false | false | ⚪ 未使用 |
| false | true | 🚫 已禁用 |

禁用徽章优先级最高,因为禁用意味着用户主动关闭了即使已引用的 Skill。

#### 3.2.2 SkillCard.vue(网格卡片)

卡片顶部状态行(替换现有可用性徽章):
- 🟢 **已使用** - 当 `item.used=true` 且未禁用时显示绿色徽章
- 🚫 **已禁用** - 当 `item.disabled=true` 时显示红色徽章(优先级高于已使用)
- ⚪ **未使用** - 当两者都不满足时显示灰色徽章

#### 3.2.3 SkillRow.vue(列表行)

当前缺可用性徽章,需补全。在名称后、点赞前增加状态徽章区,与卡片保持一致:

```
#1  SQL优化  🟢已使用  👍128  [👍]
```

#### 3.2.4 数据流

- 后端 `SkillListItem` 新增 `disabled` 字段(boolean)
- `SkillService.java` 批量查询 `skill_user_disable` 填充 `disabled`
- 前端 `types/skill.ts` 的 `SkillListItem` 增加 `disabled` 字段

### 3.3 维度数据补全(问题 5)

#### 3.3.1 补全 MockOrgService 用户组织归属

**文件**: `MockOrgService.java`

当前所有用户只归属 GROUP,需补全到四维。每个用户同时归属小组+部门+产品线+公司:

| 用户 | 小组 | 部门 | 产品线 | 公司 |
|------|------|------|--------|------|
| user_001 | 开发一组 | 研发部 | 数据产品线 | 杭研 |
| user_002 | 开发一组 | 研发部 | 数据产品线 | 杭研 |
| user_003 | 统计组 | 数据部 | 办公产品线 | 杭研 |
| approver_001 | 开发一组 | 研发部 | 数据产品线 | 杭研 |
| approver_002 | 统计组 | 数据部 | 办公产品线 | 杭研 |
| approver_003 | 开发一组 | 研发部 | 数据产品线 | 杭研 |
| demo-user | 开发一组 | 研发部 | 数据产品线 | 杭研 |

这样发布到任一维度的 Skill 都能匹配到用户。

#### 3.3.2 前端维度筛选 UI 补全

**文件**: `SkillListPage.vue`

当前只有"个人",补全为:

```ts
const dimensions = [
  { value: 'PERSONAL', label: '个人' },
  { value: 'GROUP', label: '小组' },
  { value: 'DEPARTMENT', label: '部门' },
  { value: 'PRODUCT_LINE', label: '产品线' },
  { value: 'COMPANY', label: '公司级' },
];
```

#### 3.3.3 维度字段语义变更

由于"引用即可用",`dimension` 字段不再由 `skill_publish` 推导可用性。改为:
- 个人 = 未发布到任何组织维度(默认)
- 小组/部门/产品线/公司级 = 该 Skill 有对应维度的 APPROVED 发布记录(仅作展示用)

`SkillService.java` 的 `dimension` 计算保持取首条 APPROVED 发布记录的逻辑,但仅作展示用,不影响可用性。

### 3.4 身份切换器优化(问题 1)

#### 3.4.1 优化 SkillShell.vue 用户切换体验

当前测试用户切换器藏在侧边栏,不直观。

**改动**:
1. **移到顶栏头像位置**: 在顶栏右侧放置下拉菜单,显示当前用户名 + 所属组织,点击展开用户列表
2. **显示用户信息**: 切换器显示当前用户的组织归属(如"user_001 · 开发一组/研发部")
3. **视觉标识**: 添加"测试身份"小标签,明确这是模拟数据而非真实登录

#### 3.4.2 顶栏布局

```
┌──────────────────────────────────────────────────────────────┐
│  Logo    [🔍 搜索]              [测试身份] 👤 user_001 ▾  │
│                                          ┌──────────────────┐│
│                                          │ user_001         ││
│                                          │ 开发一组/研发部   ││
│                                          ├──────────────────┤│
│                                          │ user_002         ││
│                                          │ approver_001     ││
│                                          │ ...              ││
│                                          └──────────────────┘│
└──────────────────────────────────────────────────────────────┘
```

#### 3.4.3 实现

- SkillShell.vue 顶栏右侧改用 `<select>` 或自定义下拉(保持简单,用原生 select 也可)
- 选中后仍写入 localStorage 并刷新(保留现有逻辑,只改 UI 位置)
- 从 MockOrgService 暴露 API 供前端展示用户归属

#### 3.4.4 MockOrgService 增加方法

```java
public UserInfo getUserInfo(String userId)  // 返回 userId + 所属组织名称列表
```

新增轻量 API: `GET /api/org/user-info?userId=xxx` 供前端展示用户归属。

### 3.5 其他优化点与未实现功能补全

#### 3.5.1 标签筛选补全(§11.3/§12.5.3)

**文件**: `SkillListPage.vue`

当前缺标签下拉。补全:
- 后端新增 `GET /api/skills/tags` 返回所有去重标签列表(从 `skill_manage.tags` 提取)
- 前端增加标签下拉,选中后调用 `listSkills({ tag })`

#### 3.5.2 SkillRow 补全徽章(§12.5.4)

**文件**: `SkillRow.vue`

补齐与 SkillCard 一致的徽章:可用性/已使用/已禁用、维度徽章。

#### 3.5.3 被引用人数展示(§11.5/§12.5.5)

**文件**: `SkillDetailPage.vue`

详情页展示"被 N 人引用":
- 后端 `GET /api/skills/{id}/referencers` 已存在,前端 api/skill.ts 增加调用
- 详情页 actions 区显示"被 N 人引用"

#### 3.5.4 空状态引导文案(§11.5)

各视图空状态增加引导:
- "我点赞的"为空 -> "去全部 Skill 找找感兴趣的"
- "我使用的"为空 -> "浏览全部 Skill,引用你需要的"
- "我创建的"为空 -> "创建你的第一个 Skill"

#### 3.5.5 分页补全(§11.5)

前端增加分页控件,传递 `limit/offset` 参数(后端已支持)。热门榜保持单页 Top 50。

### 3.6 不在本次修复范围

以下保持现状,不在本次改动:
- 真实鉴权(继续用 X-User-Id 请求头)
- skill_manage/skill_publish 种子数据(用户未选)
- LIKE/UNLIKE 审计入库(按原决策保持不入库)

***

## 4. 改动文件清单

### 4.1 后端(Java)

| 文件 | 改动 |
|------|------|
| `SkillService.java` | 重构 `available` 计算(引用+禁用)、移除 `availability` 筛选、`disabled` 字段填充 |
| `MockOrgService.java` | 补全 USER_ORGS 四维归属、新增 `getUserInfo` 方法 |
| `SkillController.java` | 移除 `availability` 参数、新增 `GET /api/skills/tags`、新增 `GET /api/org/user-info` |
| `SkillListQuery.java` | 移除 `availability` 字段 |
| `SkillListItem.java` | 新增 `disabled` 字段 |

### 4.2 前端(Vue)

| 文件 | 改动 |
|------|------|
| `SkillShell.vue` | 用户切换器移到顶栏、显示组织信息、测试身份标识 |
| `SkillListPage.vue` | 移除可用性下拉、补全维度下拉、补全标签下拉、分页控件、空状态引导 |
| `SkillCard.vue` | 状态徽章变更(已使用/已禁用/未使用) |
| `SkillRow.vue` | 补全状态徽章、维度徽章 |
| `SkillDetailPage.vue` | 被引用人数展示 |
| `api/skill.ts` | 移除 availability 参数、新增 tags/userInfo/referencers 调用 |
| `types/skill.ts` | SkillListItem 增加 `disabled` 字段 |

***

## 5. 测试策略

### 5.1 单元测试

- `available` 计算:引用+未禁用=true、引用+禁用=false、未引用=false
- `disabled` 字段:批量查询正确填充
- `getUserInfo`:返回正确的组织归属

### 5.2 集成测试

- 创建 Skill -> 引用 -> 列表显示"已使用"徽章
- 创建 Skill -> 禁用 -> 列表显示"已禁用"徽章
- 引用后禁用 -> 显示"已禁用"(优先级)
- 维度筛选:小组/部门/产品线/公司级均可筛选
- 用户切换:切换后状态徽章正确更新

### 5.3 验证清单

- [ ] 创建 Skill 并引用后,列表显示🟢已使用
- [ ] 禁用 Skill 后,列表显示🚫已禁用
- [ ] 移除可用性筛选下拉
- [ ] 维度下拉显示 5 个选项
- [ ] 用户切换器在顶栏,显示组织信息
- [ ] 标签筛选可用
- [ ] SkillRow 显示状态徽章
- [ ] 详情页显示被引用人数
- [ ] 空状态有引导文案
- [ ] 分页控件可用

***

## 6. 实施顺序

1. **后端数据层**: MockOrgService 补全 + SkillService.available 重构
2. **后端 API**: 移除 availability、新增 tags/userInfo 端点
3. **前端类型**: types/skill.ts 增加 disabled 字段
4. **前端组件**: SkillCard/SkillRow 徽章、SkillShell 切换器
5. **前端页面**: SkillListPage 筛选/分页、SkillDetailPage 被引用人数
6. **前端 API**: api/skill.ts 调整
