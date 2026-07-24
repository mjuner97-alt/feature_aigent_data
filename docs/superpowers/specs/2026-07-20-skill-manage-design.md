# Skill 管理平台设计文档

- **日期**: 2026-07-23 (更新)
- **状态**: Draft
- **作者**: brainstorming session
- **变更**: 2026-07-23 新增点赞功能(§4.8 / §5.1 / §5.8 / §7.6)与前端展示设计(§11)

***

## 1. 项目背景

随着企业内部 Agent 应用的发展，不同团队会沉淀大量可复用 Skill。

例如：

- SQL 查询 Skill
- 数据分析 Skill
- Excel 自动生成 Skill
- 代码审查 Skill
- 业务规则 Skill

目前 Skill 存在以下问题：

- Skill 创建后无法有效共享
- 不同部门需要不同的 Skill 使用范围
- 优秀 Skill 无法快速推广
- 缺少统一管理和追踪能力
- Agent 无法根据用户组织自动获取可用 Skill

因此建设 Skill 管理平台，实现：

- Skill 创建
- Skill 管理
- 组织级共享
- 审批控制
- 用户个性化控制
- 使用历史追踪

***

## 2. 核心设计理念

### 2.1 Skill 与组织使用关系分离

核心原则：

> Skill 是内容资产，发布关系决定谁可以使用。

模型：

```
Skill
 |
 |
 +------ Publish
          |
          |
          +---- 小组
          |
          +---- 部门
          |
          +---- 产品线
          |
          +---- 公司级

User
 |
 |
 +---- Disable
```

解释：

**Skill 本身**负责：名称、描述、内容、作者、状态

**Publish**负责：哪些组织默认使用、是否审批通过

**Disable**负责：某个人是否关闭

***

## 3. 系统整体架构

```
                 用户
                  |
                  |
              Skill管理后台
                  |
        ---------------------
        |                   |
        |                   |
   Skill Service      Publish Service
        |                   |
        |                   |
 skill_manage        skill_publish
        |
        |
 Version History Service
        |
        |
skill_version_history
        |
        |
 History Service
        |
        |
skill_operation_history
```

***

## 4. 功能模块设计

### 4.1 Skill 管理模块

负责 Skill 生命周期管理。

支持：

- 创建 Skill
- 编辑 Skill
- 查看 Skill
- 删除 Skill
- 查询 Skill
- 版本历史查看

**Skill 状态**：

| 状态 | 说明 |
|------|------|
| ACTIVE | 正常使用 |
| DISABLED | 作者主动停用 |
| DELETED | 逻辑删除 |

### 4.2 组织发布模块

控制哪些组织默认拥有这个 Skill。

**示例场景**：

张三创建 SQL 优化 Skill → 申请发布到"杭州开发三部" → 审批通过 → 开发三部所有成员默认使用

**支持范围**：

| target_type | 说明 |
|-------------|------|
| GROUP | 小组 |
| DEPARTMENT | 部门 |
| PRODUCT_LINE | 产品线 |
| COMPANY | 公司级 |

**发布状态**：

| 状态 | 说明 |
|------|------|
| PENDING | 待审批 |
| APPROVED | 已通过 |
| REJECTED | 已退回 |
| DISABLED | 已停用 |

### 4.3 审批模块

**重要**：审批对象有两类：① Skill 发布申请（§4.2）；② 已发布 Skill 的内容变更草稿（§4.3.1）。

**发布申请审批流程**：

```
创建 Skill
    ↓
申请发布 (target_type + target_id)
    ↓
按级别查询审批人
    ↓
负责人审批
    ↓
通过 -> 组织成员生效
退回 -> 申请人可修改后重新提交
```

**审批人查询逻辑**（配置化，静态配置 + 预留扩展）：

审批人通过 `MockOrgService` 的静态配置指定，不依赖外部系统，不建组织表。后续可迁移到数据库表。

```java
// org -> approver 模拟映射（key = "ORG_TYPE:org_id"）
private static final Map<String, String> ORG_APPROVER = Map.of(
    "GROUP:group_001", "approver_001",
    "GROUP:group_002", "approver_001",
    "DEPARTMENT:dept_001", "approver_003"
);
```

**借调场景**：用户提交发布申请时，可选择任意目标（不限于自己所属），系统查该目标的审批人。

#### 4.3.1 变更审批（已发布 Skill 的内容修改）

**业务场景**：作者编辑已发布的 Skill 时，变更不直接生效，而是进入草稿区待审批。审批通过后才应用到主表，组织成员始终看到已审批版本。

**数据流**：

```
作者编辑已发布 Skill S
    ↓
写入 skill_draft (skill_id=S, 草稿内容, status=PENDING)
    ↓
按 S 当前 APPROVED 发布范围查审批人集合
    ↓
任一审批人通过（或签模式） -> 旧主表存入 skill_version_history，草稿内容应用到 skill_manage，draft.status=APPROVED
任一审批人退回 -> draft.status=REJECTED，主表不变，作者可修改后重新提交（复用同一 draft 行）
```

**审批人来源**：查 `skill_publish WHERE skill_id=? AND status='APPROVED'` 得到 target 列表，再查 `MockOrgService.getApprover(orgType, orgId)` 得审批人集合。任一审批人通过即生效（或签）。

**并发约束**：同一 Skill 同时只允许一条 PENDING 草稿。重新提交时复用同一行（REJECTED -> PENDING + 更新草稿内容）。

**未发布 Skill 的编辑**：Skill 无 APPROVED 发布记录时，编辑直接更新主表（原逻辑），不经过草稿审批。

### 4.4 用户禁用模块

**业务逻辑**：

组织默认启用某 Skill → 用户可单独关闭 → 只影响自己

**示例**：

```
开发三部: 100人默认使用 SQL优化Skill
张三: disable → 仅张三不可用
其他99人: 正常可用
```

### 4.5 引用模块

支持 Skill 之间引用，用于 Skill 组合、复用和依赖关系展示。

**示例**：SQL分析Skill 引用 SQL规范Skill

**引用行为**：

```
用户 A 引用用户 B 的 Skill S
    ↓
写入 skill_reference (source_skill_id=S, target_skill_id=S, creator=A)
    ↓
用户 B 更新 Skill S
    ↓
引用者实时获取最新版本
```

**删除被引用 Skill 的处理流程**：

```
owner 尝试删除被引用的 Skill S
    ↓
系统查询 skill_reference 发现有引用者
    ↓
返回提示："该 Skill 被 N 人引用，删除后引用者将无法使用"
    ↓
owner 确认删除 → 系统发送通知给所有引用者（预留接口）
    ↓
执行删除
```

### 4.6 版本历史模块

每次编辑 Skill 时，保存旧版本到 `skill_version_history`，支持回溯和审计。

### 4.7 历史追踪模块

记录：谁、什么时候、对什么、做了什么操作。

支持的操作类型：

| 操作 | 说明 |
|------|------|
| CREATE | 创建 Skill |
| UPDATE | 修改 Skill |
| DELETE | 删除 Skill |
| PUBLISH | 申请发布 |
| APPROVE | 审批通过 |
| REJECT | 审批退回 |
| DISABLE | 禁用 Skill |
| ENABLE | 启用 Skill |
| REFERENCE | 引用 Skill |
| LIKE | 点赞 Skill |
| UNLIKE | 取消点赞 Skill |

### 4.8 点赞模块

支持用户对 Skill 点赞,点赞数作为排序与发现的核心信号(点赞多的 Skill 优先排在最前面)。

**业务规则**:

- 任何已登录用户可对任意状态为 `ACTIVE` 的 Skill 点赞(不要求当前可用,符合"全部可浏览可点赞")
- 每个用户对同一 Skill 仅能点赞一次(`skill_like` 唯一约束);再次点赞幂等无变化(不重复计数),取消点赞走 `DELETE`
- 点赞归属用户(`user_id`),支撑"我点赞的 Skill"视图
- `DELETED` / `DISABLED` 的 Skill 不出现在浏览列表,故不可被点赞
- 点赞 / 取消在单事务内完成:写 / 删 `skill_like` 一行 + `skill_manage.like_count` 原子 ±1

**排序规则**:

- 默认排序:`ORDER BY like_count DESC, updated_at DESC`(点赞数倒序,平局按更新时间倒序)
- 适用于"全部 Skill"、"分类浏览"、"热门榜";其余视图默认同此排序,可切换
- 排序控件选项:`点赞最多`(默认) / `最新更新` / `名称`

**与操作历史的关系**:

`skill_like` 表是点赞的 Source of Truth;`LIKE` / `UNLIKE` 同时作为操作类型记入 `skill_operation_history` 供审计(高频事件,可采样或异步记录)。

***

## 5. 数据库设计

### 5.1 skill_manage（Skill 主表）

```sql
CREATE TABLE skill_manage (
    id              BIGINT PRIMARY KEY,
    name            VARCHAR(128) NOT NULL,
    description     TEXT,
    content         TEXT,
    category        VARCHAR(64),
    tags            VARCHAR(512),
    owner_user_id   VARCHAR(64) NOT NULL,
    status          VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    like_count      BIGINT NOT NULL DEFAULT 0,
    created_at      DATETIME NOT NULL,
    updated_at      DATETIME NOT NULL,
    deleted_at      DATETIME NULL,
    INDEX idx_owner (owner_user_id),
    INDEX idx_status (status),
    INDEX idx_like_rank (like_count DESC, updated_at DESC)
);
```

**字段说明**：

| 字段 | 说明 |
|------|------|
| id | 主键 |
| name | Skill 名称 |
| description | 描述 |
| content | Skill 内容 |
| category | 分类 |
| tags | 标签，逗号分隔 |
| owner_user_id | 创建者 user_id |
| status | 状态：ACTIVE/DISABLED/DELETED |
| like_count | 点赞数(冗余计数,排序与热门榜用) |
| created_at | 创建时间 |
| updated_at | 更新时间 |
| deleted_at | 删除时间（软删除） |

### 5.2 skill_publish（组织发布关系表）

```sql
CREATE TABLE skill_publish (
    id                      BIGINT PRIMARY KEY,
    skill_id                BIGINT NOT NULL,
    target_type             VARCHAR(32) NOT NULL,
    target_id               VARCHAR(64) NOT NULL,
    target_name             VARCHAR(128) NOT NULL,
    status                  VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    submitter               VARCHAR(64) NOT NULL,
    approver                VARCHAR(64),
    approve_time            DATETIME,
    current_approver_user_id VARCHAR(64),
    last_approval_comment   TEXT,
    last_approval_at        DATETIME,
    created_at              DATETIME NOT NULL,
    INDEX idx_skill (skill_id),
    INDEX idx_status (status),
    INDEX idx_submitter (submitter),
    INDEX idx_approver_pending (current_approver_user_id, status)
);
```

**字段说明**：

| 字段 | 说明 |
|------|------|
| id | 主键 |
| skill_id | 关联 skill_manage.id |
| target_type | 目标类型：GROUP/DEPARTMENT/PRODUCT_LINE/COMPANY |
| target_id | 目标 ID |
| target_name | 目标名称 |
| status | 状态：PENDING/APPROVED/REJECTED/DISABLED |
| submitter | 提交人 user_id |
| approver | 审批人 user_id |
| approve_time | 审批时间 |
| current_approver_user_id | 当前待审批人 |
| last_approval_comment | 最近一次审批意见 |
| last_approval_at | 最近一次审批时间 |
| created_at | 创建时间 |

### 5.3 skill_approval（审批记录表）

```sql
CREATE TABLE skill_approval (
    id              BIGINT PRIMARY KEY,
    publish_id      BIGINT NOT NULL,
    action          VARCHAR(32) NOT NULL,
    operator        VARCHAR(64) NOT NULL,
    comment         TEXT,
    version_snapshot INT NOT NULL,
    created_at      DATETIME NOT NULL,
    INDEX idx_publish (publish_id),
    INDEX idx_operator (operator)
);
```

**字段说明**：

| 字段 | 说明 |
|------|------|
| id | 主键 |
| publish_id | 关联 skill_publish.id |
| action | 动作：SUBMIT/APPROVE/REJECT |
| operator | 操作人 user_id |
| comment | 审批意见 |
| version_snapshot | 审批时的 Skill 版本号 |
| created_at | 操作时间 |

### 5.4 skill_version_history（版本历史表）

```sql
CREATE TABLE skill_version_history (
    id              BIGINT PRIMARY KEY,
    skill_id        BIGINT NOT NULL,
    version         INT NOT NULL,
    name            VARCHAR(128),
    description     TEXT,
    content         TEXT,
    category        VARCHAR(64),
    tags            VARCHAR(512),
    edited_by       VARCHAR(64) NOT NULL,
    edit_reason     VARCHAR(256),
    created_at      DATETIME NOT NULL,
    INDEX idx_skill_version (skill_id, version DESC)
);
```

**字段说明**：

| 字段 | 说明 |
|------|------|
| id | 主键 |
| skill_id | 关联 skill_manage.id |
| version | 版本号 |
| name | 该版本的 Skill 名称 |
| description | 该版本的描述 |
| content | 该版本的内容 |
| category | 该版本的分类 |
| tags | 该版本的标签 |
| edited_by | 编辑人 user_id |
| edit_reason | 编辑原因 |
| created_at | 版本创建时间 |

### 5.5 skill_user_disable（用户禁用表）

```sql
CREATE TABLE skill_user_disable (
    id          BIGINT PRIMARY KEY,
    skill_id    BIGINT NOT NULL,
    user_id     VARCHAR(64) NOT NULL,
    created_at  DATETIME NOT NULL,
    UNIQUE INDEX uk_user_skill (user_id, skill_id)
);
```

### 5.6 skill_reference（引用关系表）

```sql
CREATE TABLE skill_reference (
    id              BIGINT PRIMARY KEY,
    source_skill_id BIGINT NOT NULL,
    target_skill_id BIGINT NOT NULL,
    creator         VARCHAR(64) NOT NULL,
    created_at      DATETIME NOT NULL,
    UNIQUE INDEX uk_source_target (source_skill_id, target_skill_id, creator)
);
```

**说明**：用户 A 引用用户 B 的 Skill → 写入一条记录 (source_skill_id=B的skill, target_skill_id=B的skill, creator=A)

### 5.7 skill_operation_history（操作历史表）

```sql
CREATE TABLE skill_operation_history (
    id          BIGINT PRIMARY KEY,
    skill_id    BIGINT,
    publish_id  BIGINT,
    operator    VARCHAR(64) NOT NULL,
    operation   VARCHAR(64) NOT NULL,
    before_data TEXT,
    after_data  TEXT,
    created_at  DATETIME NOT NULL,
    INDEX idx_skill (skill_id),
    INDEX idx_publish (publish_id),
    INDEX idx_operator_time (operator, created_at)
);
```

### 5.8 skill_like（点赞表）

```sql
CREATE TABLE skill_like (
    id          BIGINT PRIMARY KEY,
    skill_id    BIGINT NOT NULL,
    user_id     VARCHAR(64) NOT NULL,
    created_at  DATETIME NOT NULL,
    UNIQUE INDEX uk_user_skill (user_id, skill_id),
    INDEX idx_skill (skill_id),
    INDEX idx_user (user_id)
);
```

**字段说明**：

| 字段 | 说明 |
|------|------|
| id | 主键 |
| skill_id | 关联 skill_manage.id |
| user_id | 点赞用户 user_id |
| created_at | 点赞时间 |

**说明**：`UNIQUE INDEX uk_user_skill` 保证每用户对同一 Skill 仅一条点赞记录。`skill_manage.like_count` 为冗余计数,列表排序与热门榜直接读它,无需聚合 `skill_like`。

***

## 6. Skill 可用性计算

Agent 调用时，输入 userId，按以下步骤计算可用 Skill：

**第一步**：查询用户所属组织（部门、小组、产品线）

**第二步**：查询发布关系

```sql
SELECT * FROM skill_publish
WHERE status = 'APPROVED'
AND target_id IN (用户所属组织列表)
```

得到：Skill A、Skill B、Skill C

**第三步**：排除用户禁用

```sql
SELECT skill_id FROM skill_user_disable WHERE user_id = ?
```

过滤掉禁用项。

**最终**：返回用户可用 Skill 列表

> **可见性 vs 可用性**：前端"全部 Skill"展示所有 `ACTIVE` Skill(可见即可点赞 / 查看);而"可用"(本节计算)决定该 Skill 能否被当前用户的 Agent 调用、能否被引用。两者解耦——可用性仅作为卡片徽章与"引用"操作的门槛,不限制浏览与点赞。

***

## 7. API 设计

### 7.1 Skill 管理 API

| 方法 | 路径 | 说明 | 权限 |
|------|------|------|------|
| POST | `/api/skills` | 创建 Skill | 任何已登录用户 |
| PUT | `/api/skills/{id}` | 编辑 Skill | owner |
| GET | `/api/skills/{id}` | 查询 Skill 详情 | 任何用户 |
| GET | `/api/skills` | Skill 列表(视图/排序/筛选,见下) | 任何用户 |
| DELETE | `/api/skills/{id}` | 删除 Skill（软删除） | owner |
| GET | `/api/skills/{id}/versions` | 版本历史 | 任何用户 |

**列表查询参数**（`GET /api/skills`）：

| 参数 | 说明 | 取值 |
|------|------|------|
| view | 视图 | `all`(默认,全部 ACTIVE) / `used`(我引用的) / `liked`(我点赞的) / `created`(我创建的) / `popular`(热门榜) |
| sort | 排序 | `likes`(默认,点赞数倒序) / `updated` / `name` |
| category | 分类筛选 | category 值 |
| tag | 标签筛选 | tag 值 |
| availability | 可用性筛选 | `all`(默认) / `available`(仅我当前可用) |
| keyword | 关键词 | 匹配 name / description |
| limit / offset | 分页 | 整数;`popular` 默认 limit=50 |

返回每行额外含:`likeCount`、`liked`(我是否已点赞)、`used`(我是否已引用)、`available`(我当前是否可用)、`rank`(仅 `popular` 视图,1-based)。

### 7.2 发布与审批 API

| 方法 | 路径 | 说明 | 权限 |
|------|------|------|------|
| POST | `/api/skills/{id}/publish` | 申请发布 | owner |
| POST | `/api/publish/{id}/approve` | 审批通过 | 对应级别审批人 |
| POST | `/api/publish/{id}/reject` | 审批退回 | 对应级别审批人 |
| GET | `/api/publish/pending` | 待我审批的发布申请列表 | 审批人 |
| GET | `/api/publish/{id}/approvals` | 审批历史 | 任何用户 |

**申请发布请求体**：

```json
{
    "targetType": "GROUP",
    "targetId": "dev3",
    "targetName": "开发三部"
}
```

### 7.3 用户禁用 API

| 方法 | 路径 | 说明 | 权限 |
|------|------|------|------|
| POST | `/api/skills/{id}/disable` | 个人禁用 | 任何用户 |
| DELETE | `/api/skills/{id}/disable` | 取消禁用 | 已禁用用户 |

### 7.4 引用 API

| 方法 | 路径 | 说明 | 权限 |
|------|------|------|------|
| POST | `/api/skills/{id}/reference` | 引用 Skill | 任何用户 |
| DELETE | `/api/skills/{id}/reference` | 取消引用 | 已引用用户 |
| GET | `/api/skills/my-references` | 我引用的 Skill 列表 | 任何用户 |
| GET | `/api/skills/{id}/referencers` | 引用该 Skill 的用户列表 | 仅 owner |

### 7.5 认证说明

所有 API 中的 userId 从认证上下文（如 Spring Security）获取，**不通过 URL 参数传递**，避免身份伪造风险。

### 7.6 点赞 API

| 方法 | 路径 | 说明 | 权限 |
|------|------|------|------|
| POST | `/api/skills/{id}/like` | 点赞 | 任何已登录用户(Skill 需 ACTIVE) |
| DELETE | `/api/skills/{id}/like` | 取消点赞 | 已点赞用户 |
| GET | `/api/skills/{id}/like` | 查询点赞状态与计数 | 任何用户 |

点赞 / 取消为幂等 toggle:重复 `POST`(已点赞)返回成功且 `liked=true`,`like_count` 不变(受唯一约束保护);`DELETE` 同理。`like_count` 仅在状态真正变更时 ±1(事务内)。Skill 需为 `ACTIVE`,`DELETED` 返回 404、`DISABLED` 返回 403。

***

## 8. 错误处理

| 异常 | HTTP 状态 | 场景 |
|------|----------|------|
| SkillNotFoundException | 404 | Skill 不存在 |
| SkillAccessDeniedException | 403 | 无编辑/审批权限 |
| SkillNameConflictException | 409 | name 全局唯一冲突 |
| AlreadyReferencedException | 409 | 已引用该 Skill |
| NotReferencedException | 409 | 未引用该 Skill |

***

## 9. 测试策略

### 9.1 单元测试

- 审批流状态机流转
- 引用/取消引用逻辑
- 权限判定矩阵
- 点赞/取消点赞幂等、`like_count` 原子增减、唯一约束防重
- 列表排序:`like_count DESC, updated_at DESC` 平局处理

### 9.2 集成测试

- 完整 CRUD 流程
- 审批流：提交 → 通过/退回 → 编辑 → 重新提交
- 借调场景：一人多组多部门的权限
- 个人禁用：不影响他人
- 引用功能：引用/取消引用/删除被引用 Skill 的提醒流程
- 点赞功能:点赞排序(点赞多排前)、热门榜 Top N、"我点赞的"视图、不可用 Skill 可点赞、`DISABLED`/`DELETED` 不可点赞

### 9.3 测试数据

覆盖借调场景（user_001 同时在 group_001 和 group_002）和引用场景（user_001 引用 user_002 的 Skill）。

***

## 10. 开放问题

1. 杭研级审批人具体 ID（当前配置为占位符 `xxx`）
2. 产品线是否需要独立 scope（当前设计保留，但需确认是否有必要）
3. 删除被引用 Skill 时的通知渠道（企业微信/邮件/消息队列等）
4. `skill_reference` 的 source / target 语义(当前示例 source==target):"我使用的 Skill"复用该表,需确认 creator=我 即代表"我使用过"
5. 热门榜 Top N 默认条数(当前 50)与刷新策略
6. "我创建的"视图是否展示 `DISABLED` 状态的 Skill(便于作者管理)
7. 点赞事件是否全量写入 `skill_operation_history`(审计 vs 高频噪声)
8. `like_count` 漂移修复:是否提供定时校验 / 管理端重算接口

***

## 11. 前端展示设计

### 11.1 整体布局(左侧导航栏)

采用**左侧导航栏 + 右侧内容区**布局。左侧承载所有视图入口,右侧为筛选条 + Skill 列表。

```
┌──────────────────────────────────────────────────────────────┐
│  Logo          [🔍 搜索 skill / 作者 / 标签]         👤 头像 │  顶栏
├────────────┬─────────────────────────────────────────────────┤
│ 全部 Skill │  排序:[点赞最多▼] 可用性:[全部▼] [▦网格|≡列表]   │
│ 我使用的   │  分类:[全部▼] 标签:[全部▼]                       │  筛选条
│ 我点赞的   ├─────────────────────────────────────────────────┤
│ 我创建的   │  ┌────────┐ ┌────────┐ ┌────────┐                │
│ 热门榜     │  │🟢可用  │ │⚪不可用│ │🟢可用  │                │
│ ─────────  │  │SQL优化 │ │Excel  │ │代码审查│                │  内容区
│ 分类浏览   │  │👍128   │ │👍96   │ │👍74    │                │ (网格/列表)
│            │  │张三·数据│ │李四·办公│ │王五·研发│               │
│ ─────────  │  └────────┘ └────────┘ └────────┘                │
│ 待我审批*  │                                                 │
└────────────┴─────────────────────────────────────────────────┘
   * 待我审批:仅审批人可见的管理入口
```

左侧导航项:

- **全部 Skill**:平台所有 ACTIVE Skill,默认按点赞数排序(广场)
- **我使用的 Skill**:我引用过的 Skill(复用 `skill_reference`,creator=我)
- **我点赞的 Skill**:我点过赞的 Skill
- **我创建的 Skill**:我 own 的 Skill
- **热门榜**:点赞数 Top N(默认 50)
- **分类浏览**:按 category / tags 维度浏览(进入后等于"全部" + 分类筛选)
- **待我审批**(仅审批人):待我审批的发布申请,沿用现有审批 API

### 11.2 Skill 卡片(网格 / 列表可切换)

内容区支持**网格**与**列表**两种密度一键切换,偏好持久化(用户级配置)。不同视图默认密度不同:全部 / 分类默认网格,热门榜 / 我使用的默认列表(带排名)。

卡片元素:

- **可用性徽章**:🟢 可用 / ⚪ 不可用(按第 6 节可用性计算,针对当前用户)
- **点赞数 + 点赞按钮**:👍 计数;已点赞时按钮高亮;点击即时乐观更新
- **已使用标记**:我已引用该 Skill 时显示"已使用"
- **名称 / 描述**(描述截断)/ **作者 · 分类** / **标签**
- **排名**(列表与热门榜):#1 #2 …,Top 3 可特殊配色

网格卡片:

```
┌─────────────────┐
│ 🟢可用    👍 128 │
│ SQL 查询优化     │
│ 自动优化慢查询…  │
│ 张三 · 数据分析  │
│ #sql #优化  已使用│
│         [👍已点赞]│
└─────────────────┘
```

列表行:

```
┌──────────────────────────────────────────────────────┐
│ #1  SQL 查询优化  🟢可用  已使用   👍128   [👍已点赞] │
│     自动优化慢查询 · 张三 · 数据分析 · #sql #优化      │
└──────────────────────────────────────────────────────┘
```

不可用的 Skill:徽章置灰,禁用"引用"操作,但保留"点赞"与"查看详情"。

### 11.3 排序与筛选

- **排序**:`点赞最多`(默认) / `最新更新` / `名称`,所有视图统一,对应后端 `sort` 参数
- **筛选**:分类、标签、可用性(全部 / 仅可用)、关键词搜索
- 筛选与视图组合,例:"我使用的 + 仅可用 + 点赞最多"

### 11.4 各视图查询逻辑

| 视图 | 数据来源 | 默认排序 |
|------|----------|----------|
| 全部 Skill | `skill_manage` WHERE status='ACTIVE' | like_count DESC, updated_at DESC |
| 我使用的 | `skill_reference` creator=我 -> join skill_manage | 同上 |
| 我点赞的 | `skill_like` user_id=我 -> join skill_manage | 同上 |
| 我创建的 | `skill_manage` owner_user_id=我 | 同上 |
| 热门榜 | `skill_manage` WHERE status='ACTIVE' | like_count DESC, updated_at DESC LIMIT 50 |
| 分类浏览 | 全部 + category / tag 过滤 | 同上 |

**可用性标记批量计算**:对当前页 Skill 集合一次性取--用户所属组织、该批 Skill 的 APPROVED 发布关系、用户的 `skill_user_disable`--逐行打 `available` 标记,避免逐行查询。

### 11.5 交互细节

- **点赞乐观更新**:点击立即 UI +1 并高亮,后端确认失败则回滚;并发受唯一约束保护
- **网格 / 列表切换**:用户偏好持久化(localStorage 或用户配置),各视图可独立默认
- **分页**:无限滚动或分页(`limit / offset`);热门榜为单页 Top N
- **空状态**:各视图空时给出引导(如"我点赞的"为空 -> "去全部 Skill 找找感兴趣的")
- **Skill 详情页**:点卡片进入,展示完整 content、点赞按钮、引用按钮、版本历史入口、被引用人数、(作者可见)操作历史

### 11.6 点赞数据流

```
用户点 👍
  ↓ (前端乐观更新)
POST /api/skills/{id}/like
  ↓
事务:
  INSERT skill_like(user_id, skill_id)   -- 唯一约束防重
  UPDATE skill_manage SET like_count = like_count + 1 WHERE id = ?
  INSERT skill_operation_history(operation='LIKE')   -- 可选 / 异步
  ↓
返回最新 likeCount + liked=true
  ↓
前端确认 / 回滚
```

### 11.7 实现架构(前端工程化)

采用与现有 agent 监控 UI 一致的 **Vue 3 + vue-router + Vite** 技术栈,**不新增独立前端工程**;沿用已有的"合并构建"模式--生产环境一个 Spring Boot jar 同时提供 API 与页面,非前后端分离部署。

**前端(`analysis-project/frontend`)**:

- 路由:在 `main.ts` 路由表新增 Skill 管理路由,使用独立布局组件 `SkillShell.vue`(§11.1 的左侧导航栏),与现有 `AppShell`(会话侧栏)平级:
  - `/skills` -> 全部 Skill(默认)
  - `/skills/used` / `/skills/liked` / `/skills/created` / `/skills/popular` / `/skills/category`
  - `/skills/:id` -> Skill 详情页
  - `/skills/approvals` -> 待我审批(仅审批人)
- 视图切换 / 排序 / 筛选由路由 query 或组件状态管理,数据请求统一走 `api/skill.ts`
- 卡片网格 / 列表切换:新增 `SkillCard.vue`(网格)与 `SkillRow.vue`(列表),密度偏好持久化到 localStorage

**API 接入**:

- 新增 `api/skill.ts`,调用第 7 节 `/api/skills...` 端点(列表 / 点赞 / 引用 / 发布等)
- 开发期:Vite 5173 代理需把 `/api` 一并转发到 `http://localhost:8081`(在 `vite.config.ts` 的 `server.proxy` 增加 `/api` 项);生产期同源,无需代理
- 构建产物仍输出到 `src/main/resources/static`(`vite build`,`emptyOutDir: true`)

**后端(`analysis-project` Java)**:

- 框架:Spring Boot(`spring-boot-starter-web`)+ MyBatis(`mybatis-spring-boot-starter`)+ Flyway 迁移,与现有代码一致
- 控制器:新增 `SkillController` / `SkillLikeController` 等(`@RestController`,路径 `/api/skills`),返回 JSON;`userId` 从 Spring Security 上下文取(§7.5)
- 数据库变更:用 Flyway 迁移脚本新增 `skill_like` 表、给 `skill_manage` 加 `like_count` 列与索引(置于 `src/main/resources/db/migration`,如 `V20260723__add_skill_like.sql`)
- 点赞排序、热门榜直接走 `idx_like_rank` 索引;可用性标记按 §11.4 批量计算

**部署**:`mvn package` 产出单个 jar,前端产物已在内;`java -jar` 即同时提供页面与 API,无需单独前端服务器。

***

## 12. 缺口补全设计(2026-07-24)

本章补全原设计中未实现的模块:发布与审批(§4.2/4.3)、用户禁用(§4.4)、版本历史(§4.6)、操作历史(§4.7)、可用性计算(§6)、前端筛选与审批页(§11)。

### 12.1 补全决策汇总

| 决策项 | 选择 |
|--------|------|
| 审批对象范围 | 发布申请 + 已发布 Skill 内容变更审批 |
| 变更生效策略 | 草稿区 + 审批后应用(主表始终是已审批版本) |
| 补全范围 | 全部缺口一次补全 |
| 审批人配置 | 静态配置(MockOrgService)+ 预留扩展 |
| 组织数据来源 | 模拟数据(硬编码),不建组织表,留入口 |
| 实施方案 | 分层递进:数据层 -> 后端服务层 -> 前端层 |
| 变更审批人 | 或签(任一审批人通过即生效) |
| 重新提交 | 复用同一行(REJECTED -> PENDING) |
| 单一 PENDING 约束 | 同一 Skill 同时只允许一条 PENDING 草稿/发布申请 |
| LIKE/UNLIKE 审计 | 不写入操作历史 |
| 待我审批入口 | 导航栏始终显示,非审批人显示空态 |
| 禁用操作位置 | 详情页按钮 + 列表卡片可用性徽章 |

### 12.2 新增数据库表

不新增组织表(skill_org / skill_user_org),组织数据用 MockOrgService 模拟。新增以下 6 张表:

#### 12.2.1 skill_publish(发布关系表,原 §5.2)

沿用原 §5.2 设计,无变更。

#### 12.2.2 skill_approval(审批记录表,原 §5.3)

沿用原 §5.3 设计,无变更。`action` 取值扩展:`SUBMIT`/`APPROVE`/`REJECT`,同时覆盖发布申请审批与变更草稿审批(通过 `publish_id` 或 `draft_id` 关联,二者其一非空)。

为支撑变更草稿审批,新增 `draft_id` 列:

```sql
ALTER TABLE skill_approval ADD COLUMN draft_id BIGINT NULL AFTER publish_id;
ALTER TABLE skill_approval ADD INDEX idx_draft (draft_id);
```

#### 12.2.3 skill_draft(变更草稿表,新增)

```sql
CREATE TABLE IF NOT EXISTS skill_draft (
    id              BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    skill_id        BIGINT NOT NULL,
    name            VARCHAR(128),
    description     TEXT,
    content         TEXT,
    category        VARCHAR(64),
    tags            VARCHAR(512),
    status          VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    submitter       VARCHAR(64) NOT NULL,
    approver        VARCHAR(64),
    approve_comment TEXT,
    submitted_at    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    approved_at     TIMESTAMP NULL,
    KEY idx_skill (skill_id),
    KEY idx_status (status),
    KEY idx_submitter (submitter)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

单一 PENDING 约束由应用层保证(提交前 `SELECT ... WHERE skill_id=? AND status='PENDING'` 检查)。

#### 12.2.4 skill_version_history(版本历史表,原 §5.4)

沿用原 §5.4 设计,无变更。

#### 12.2.5 skill_operation_history(操作历史表,原 §5.7)

沿用原 §5.7 设计,无变更。LIKE/UNLIKE 不写入此表。

#### 12.2.6 skill_user_disable(用户禁用表,原 §5.5)

沿用原 §5.5 设计,无变更。

### 12.3 后端服务设计

#### 12.3.1 MockOrgService(组织数据模拟)

不建组织表,用硬编码模拟数据。提供两个核心查询:

```java
@Service
public class MockOrgService {
    public record OrgRef(String orgType, String orgId) {}

    // user -> orgs 模拟映射
    private static final Map<String, List<OrgRef>> USER_ORGS = Map.of(
        "user_001", List.of(new OrgRef("GROUP","group_001"), new OrgRef("GROUP","group_002")),
        "user_002", List.of(new OrgRef("GROUP","group_001")),
        "approver_001", List.of(new OrgRef("GROUP","group_001")),
        "demo-user", List.of(new OrgRef("GROUP","group_001"))
    );

    // org -> approver 模拟映射(key = "ORG_TYPE:org_id")
    private static final Map<String, String> ORG_APPROVER = Map.of(
        "GROUP:group_001", "approver_001",
        "GROUP:group_002", "approver_001",
        "DEPARTMENT:dept_001", "approver_003"
    );

    public List<OrgRef> getUserOrgs(String userId) { ... }
    public String getApprover(String orgType, String orgId) { ... }
}
```

#### 12.3.2 新增服务一览(按依赖序)

| 服务 | 职责 | 依赖 |
|------|------|------|
| `MockOrgService` | 组织数据 + 审批人查询 | 无 |
| `SkillOperationHistoryService` | 审计日志记录(不含 LIKE/UNLIKE) | 无 |
| `SkillVersionHistoryService` | 版本快照存取 | SkillManageMapper |
| `SkillPublishService` | 发布申请 / 审批 / 待审列表 | MockOrgService, SkillOperationHistoryService |
| `SkillDraftService` | 变更草稿提交 / 审批 / 应用到主表 | SkillManageMapper, SkillVersionHistoryService, MockOrgService, SkillOperationHistoryService |
| `SkillUserDisableService` | 个人禁用 / 取消禁用 | SkillOperationHistoryService |

#### 12.3.3 SkillService.update 改造

编辑 Skill 时区分两种情况:

```
SkillService.update(id, patch, userId):
  s = get(id)
  assertOwner(s, userId)
  if hasApprovedPublish(s.id):
      -> 转发到 SkillDraftService.submitDraft(id, patch, userId)
      -> 返回 {message: "变更已提交审批", draftId: ...}
  else:
      -> 存旧版本到 skill_version_history
      -> 更新 skill_manage 主表
      -> 记录操作历史 UPDATE
      -> 返回更新后的 Skill
```

#### 12.3.4 SkillDraftService 审批通过逻辑

```
approve(draftId, approverId, comment):
  draft = get(draftId)
  assert draft.status == PENDING
  assert approverId 是该 Skill 任一 APPROVED 发布范围的审批人(或签)
  -> 存旧主表内容到 skill_version_history
  -> 把草稿内容应用到 skill_manage 主表
  -> draft.status = APPROVED, approver = approverId, approved_at = now
  -> 记录操作历史 APPROVE
```

#### 12.3.5 可用性计算接入 SkillService.list

当前 `SkillListItem.available` 恒为 true。改为批量计算:

1. 取当前页所有 skill_id
2. 查 `skill_publish WHERE skill_id IN (...) AND status='APPROVED'` 得 skill -> target 映射
3. `MockOrgService.getUserOrgs(userId)` 得用户组织集合
4. 交集非空 = available
5. 排除 `skill_user_disable WHERE user_id=? AND skill_id IN (...)` 的记录

#### 12.3.6 SkillListQuery 扩展

新增 `availability` 参数(原 §7.1 已列但未实现):

```java
private String availability;  // all(默认) / available(仅我当前可用)
```

SkillListPage 前端筛选条新增"可用性"下拉。

### 12.4 API 设计(补全)

#### 12.4.1 发布与审批 API(原 §7.2,补全实现)

| 方法 | 路径 | 说明 | 权限 |
|------|------|------|------|
| POST | `/api/skills/{id}/publish` | 申请发布 | owner |
| POST | `/api/publish/{id}/approve` | 审批通过 | 对应级别审批人 |
| POST | `/api/publish/{id}/reject` | 审批退回 | 对应级别审批人 |
| GET | `/api/publish/pending` | 待我审批列表 | 审批人 |
| GET | `/api/publish/{id}/approvals` | 审批历史 | 任何用户 |

#### 12.4.2 用户禁用 API(原 §7.3,补全实现)

| 方法 | 路径 | 说明 | 权限 |
|------|------|------|------|
| POST | `/api/skills/{id}/disable` | 个人禁用 | 任何用户 |
| DELETE | `/api/skills/{id}/disable` | 取消禁用 | 已禁用用户 |

#### 12.4.3 变更审批 API(新增)

| 方法 | 路径 | 说明 | 权限 |
|------|------|------|------|
| GET | `/api/skills/{id}/draft` | 查看当前草稿 | owner / 审批人 |
| POST | `/api/skills/{id}/draft/approve` | 审批通过草稿 | 或签审批人 |
| POST | `/api/skills/{id}/draft/reject` | 审批退回草稿 | 或签审批人 |
| GET | `/api/draft/pending` | 待我审批的草稿列表 | 审批人 |

#### 12.4.4 版本历史 API(原 §7.1,补全实现)

| 方法 | 路径 | 说明 | 权限 |
|------|------|------|------|
| GET | `/api/skills/{id}/versions` | 版本历史列表 | 任何用户 |

#### 12.4.5 引用 API 补全(原 §7.4 缺失项)

| 方法 | 路径 | 说明 | 权限 |
|------|------|------|------|
| GET | `/api/skills/my-references` | 我引用的 Skill 列表 | 任何用户 |
| GET | `/api/skills/{id}/referencers` | 引用该 Skill 的用户列表 | 仅 owner |

### 12.5 前端设计(补全)

#### 12.5.1 导航栏新增

[SkillShell.vue](file:///d:/deng/feature_aigent_data/analysis-project/frontend/src/components/SkillShell.vue) 导航项增加"待我审批":

```
- 全部 Skill
- 我使用的
- 我点赞的
- 我创建的
- 热门榜
- 分类浏览
- 待我审批      <- 新增,始终显示,非审批人显示空态
```

#### 12.5.2 新增路由

- `/skills/approvals` -> 审批列表页(待我审批的发布申请 + 变更草稿)
- `/skills/approvals/:id` -> 审批详情页(查看 Skill 内容、审批意见输入、通过/退回按钮)

#### 12.5.3 筛选条补全(§11.3)

[SkillListPage.vue](file:///d:/deng/feature_aigent_data/analysis-project/frontend/src/pages/skill/SkillListPage.vue) 当前缺标签筛选与可用性筛选。补全:

- **标签下拉**(tag):从后端获取标签列表(或前端硬编码常见标签),调用 `listSkills({ tag })`
- **可用性下拉**(availability):全部 / 仅可用,调用 `listSkills({ availability })`

#### 12.5.4 卡片元素补全(§11.2)

[SkillCard.vue](file:///d:/deng/feature_aigent_data/analysis-project/frontend/src/components/SkillCard.vue) / [SkillRow.vue](file:///d:/deng/feature_aigent_data/analysis-project/frontend/src/components/SkillRow.vue) 补全:

- **可用性徽章**:🟢 可用 / ⚪ 不可用(基于 `item.available`)
- **已使用标记**:我已引用该 Skill 时显示"已使用"
- **Top3 特殊配色**:热门榜前 3 名特殊样式

#### 12.5.5 详情页补全

[SkillDetailPage.vue](file:///d:/deng/feature_aigent_data/analysis-project/frontend/src/pages/skill/SkillDetailPage.vue) 补全:

- **禁用/启用按钮**:actions 区新增,已禁用显示"启用",未禁用显示"禁用"
- **版本历史面板**:展示 `GET /api/skills/{id}/versions` 结果
- **变更草稿提示**:已发布 Skill 编辑后,显示"变更已提交审批"提示
- **草稿审批入口**:审批人在详情页看到当前草稿内容 + 通过/退回按钮

### 12.6 错误处理(补全)

| 异常 | HTTP 状态 | 场景 |
|------|----------|------|
| DraftAlreadyPendingException | 409 | 该 Skill 已有 PENDING 草稿 |
| DraftNotFoundException | 404 | 草稿不存在 |
| NotApproverException | 403 | 无审批权限 |
| PublishAlreadyApprovedException | 409 | 发布申请已通过,不可重复审批 |

### 12.7 实施分层(方案 A:分层递进)

**第 1 层 - 数据基础**:
- Flyway 迁移:skill_publish、skill_approval(+draft_id)、skill_draft、skill_version_history、skill_operation_history、skill_user_disable
- Entity / Mapper / Mapper XML

**第 2 层 - 后端服务**:
- MockOrgService -> SkillOperationHistoryService -> SkillVersionHistoryService -> SkillPublishService -> SkillDraftService -> SkillUserDisableService
- SkillService.update 改造 + list 可用性计算接入
- SkillListQuery 扩展 availability
- Controller:SkillPublishController / SkillDraftController / SkillUserDisableController

**第 3 层 - 前端**:
- 筛选条(tag + availability) -> 审批页(列表 + 详情) -> 草稿编辑提示 -> 禁用按钮 + 徽章 -> 版本历史面板

### 12.8 测试策略(补全)

#### 12.8.1 单元测试

- 发布申请状态机:PENDING -> APPROVED/REJECTED,REJECTED -> PENDING(重新提交)
- 变更草稿状态机:同上
- 单一 PENDING 约束:已有 PENDING 时再提交抛 DraftAlreadyPendingException
- 或签审批:任一审批人通过即生效
- 可用性计算:用户组织与发布关系交集 + 禁用排除
- 版本快照:编辑时旧版本正确存入

#### 12.8.2 集成测试

- 发布流:创建 -> 申请发布 -> 审批通过 -> 组织成员可用
- 变更审批流:已发布 Skill -> 编辑 -> 草稿待审批 -> 审批通过 -> 主表更新 -> 版本历史 +1
- 变更退回流:草稿 REJECTED -> 重新提交 -> 通过
- 用户禁用:禁用后 available=false,取消禁用后恢复
- 借调场景:user_001 在两个组,任一组发布都可用
