# Skill 用户隔离与统一加载设计文档

- **日期**: 2026-07-28
- **状态**: Draft
- **作者**: brainstorming session
- **关联**: 2026-07-20-skill-manage-design.md, 2026-07-27-skill-fix-design.md

***

## 1. 项目背景

### 1.1 现状

当前系统的 Skill 体系有三类来源:

| 来源 | 文件目录 | skill_index.source | skill_manage 表 | 用户隔离 |
|------|----------|-------------------|----------------|----------|
| 内置元技能 | `skills/` | (不走 skill_index) | 无 | 全局共享 |
| 自动合成 | `skills-auto/` | `auto_synthesized` | 无 | 全局共享 |
| 用户页面创建 | `skills-user/` | `user_generated` | 有(owner_user_id) | 无隔离 |
| Agent 调用 save_skill | `skills-user/` | `user_generated` | **无** | 无隔离 |

核心问题:

1. **检索层无用户隔离**: `skill_index` 表没有 `owner_user_id` 列,`SkillRetrievalHook` -> `SkillVectorIndex` 的查询只按 `source` 过滤,不按用户过滤。用户 A 创建的 skill,用户 B 也能通过对话检索到。
2. **Agent 创建的 skill 不进管理页面**: `SkillSaveTool`(Agent 调用的 `save_skill` 工具)只写 `skill_index` 表 + 文件系统,不写 `skill_manage` 表,因此管理页面看不到 Agent 帮用户创建的 skill。
3. **引用机制不完整**: `skill_reference` 表只记录引用关系(creator -> target_skill_id),没有把 skill 内容复制到引用者目录,引用后引用者仍然检索不到被引用的 skill。

### 1.2 需求

1. **用户隔离 user_generated skill**: 每个用户只能检索到自己创建的 user_generated skill + 全局共享的 auto_synthesized skill。用户能在管理页面看到别人的 skill,但不能通过对话检索到。引用别人的 skill 后,该 skill 复制到自己的目录,就能被自己检索到。
2. **Agent 创建的 skill 进管理页面**: Agent 调用 `save_skill` 创建的 skill 也要写入 `skill_manage` 表,让用户在管理页面能看到 Agent 帮他创建的 skill。

***

## 2. 核心设计理念

### 2.1 隔离范围

> 仅隔离 `user_generated` skill;`auto_synthesized` skill 保持全局共享。

- `user_generated` skill:按 `owner_user_id` 隔离,用户只能检索到自己的
- `auto_synthesized` skill:`owner_user_id = NULL`,所有用户共享
- 内置 `skills/` skill:不走 `skill_index` 检索,不受影响

### 2.2 可见性 vs 可检索性

> 管理页面可见 ≠ 对话可检索

- **管理页面可见**: 所有用户都能在管理页面看到所有 `ACTIVE` 状态的 skill(含别人的)
- **对话可检索**: 只能检索到 `owner_user_id = 自己` 的 user_generated skill + 全局 auto skill
- **引用即复制**: 用户引用别人的 skill 后,skill 内容复制到自己的目录 + `skill_index` 新增一条 `owner_user_id = 自己` 的行,此后可检索

### 2.3 两种创建路径统一

> 页面创建和 Agent 创建的 skill 都要写 `skill_manage` 表 + `skill_index` 表 + 文件系统。

- 页面创建:已有路径,补充 `owner_user_id` 到 `skill_index`
- Agent 创建:新增写 `skill_manage` 表的逻辑

***

## 3. 数据库 Schema 变更

### 3.1 `skill_index` 表新增 `owner_user_id` 列

**Flyway 迁移文件**: `V20260728.1__skill_index_add_owner_user_id.sql`

```sql
-- PR5: Skill 用户隔离 - 新增 owner_user_id 列
-- NULL = 全局共享(auto_synthesized 或遗留数据)
-- 非 NULL = 仅该用户可检索
ALTER TABLE skill_index
  ADD COLUMN owner_user_id VARCHAR(64) DEFAULT NULL
  COMMENT 'PR5: skill owner for isolation; NULL = global (auto_synthesized or legacy)';

CREATE INDEX idx_owner_user_id ON skill_index(owner_user_id);
```

同时在 `SkillIndexRepository.ensureTable()` 中追加 idempotent ALTER(与现有 `tool_sequence_fingerprint`、`source` 列的兜底模式一致),保证不走 Flyway 的环境也能自愈:

```java
try {
    s.execute("ALTER TABLE skill_index ADD COLUMN owner_user_id VARCHAR(64) DEFAULT NULL COMMENT 'PR5: skill owner for isolation; NULL = global (auto_synthesized or legacy)'");
} catch (SQLException e) { /* column already exists */ }
try {
    s.execute("CREATE INDEX idx_owner_user_id ON skill_index(owner_user_id)");
} catch (SQLException e) { /* index already exists */ }
```

**DDL 常量同步更新**: 在 `SkillIndexRepository.DDL` 的 `CREATE TABLE` 语句中加入 `owner_user_id` 列定义和索引,保证全新环境一次建好。

### 3.2 `owner_user_id` 语义约定

| 场景 | owner_user_id 值 | 检索可见性 |
|------|-----------------|-----------|
| auto_synthesized skill | NULL | 所有用户可见 |
| 遗留 user_generated skill | NULL(迁移后) | 所有用户可见(向后兼容) |
| 用户创建的 user_generated skill | userId | 仅该用户可见 |
| 引用复制的 skill | 引用者 userId | 仅引用者可见 |

### 3.3 `skill_index.name` 命名规则

`name` 是 PRIMARY KEY,必须全局唯一。为避免不同用户创建同名 skill 冲突,引入命名前缀:

| 创建方式 | name 格式 | 示例 |
|---------|----------|------|
| 页面创建 | `page_<skillId>` | `page_42` |
| Agent 调用 save_skill | `usr_<userId>_<safeName>` | `usr_user_001_quality_query` |
| 引用复制 | `ref_<originalName>__u_<userId>` | `ref_page_42__u_user_001` |
| 自动合成 | 原样(`<safeName>`) | `quality_query_analysis` |

**向后兼容**: 现有 `page_<id>` 格式的行,在迁移脚本中回填 `owner_user_id`(通过 JOIN `skill_manage.retrieval_name` 关联拿到 `owner_user_id`)。

### 3.4 数据迁移脚本

`V20260728.2__skill_index_backfill_owner_user_id.sql`:

```sql
-- 回填现有 page_<id> 行的 owner_user_id
UPDATE skill_index si
  JOIN skill_manage sm ON si.name = sm.retrieval_name
  SET si.owner_user_id = sm.owner_user_id
  WHERE si.source = 'user_generated'
    AND si.owner_user_id IS NULL
    AND sm.owner_user_id IS NOT NULL;
```

未关联上的行(owner_user_id 仍为 NULL)视为全局共享,不阻断功能。

***

## 4. 检索层改造

### 4.1 `SkillVectorIndex` 增加 userId 过滤

#### 4.1.1 `CachedSkill` record 增加字段

```java
private record CachedSkill(
    String name, String description, float[] embedding,
    float norm, String source, String ownerUserId) {}
```

#### 4.1.2 `loadAllActiveSkills()` 读取 `owner_user_id`

SQL 增加 `owner_user_id` 列:

```sql
SELECT name, description, embedding, source, owner_user_id
  FROM skill_index WHERE status = 'active' AND embedding IS NOT NULL
```

#### 4.1.3 `findByFingerprint` 增加重载

```java
/**
 * PR5 - user-scoped L1 lookup. Matches skills where:
 *   (owner_user_id = userId OR owner_user_id IS NULL) AND source = ?
 */
public Optional<String> findByFingerprint(String fingerprint, String source, String userId)
```

SQL(当 userId 非 null 时):

```sql
SELECT name FROM skill_index
  WHERE fingerprint = ? AND status = 'active' AND source = ?
    AND (owner_user_id = ? OR owner_user_id IS NULL)
  LIMIT 1
```

当 userId 为 null 时,**不退化为不过滤**,而是只匹配 `owner_user_id IS NULL` 的行(即全局共享的 auto/遗留数据)。这保证匿名用户不会意外检索到别人的 skill。SQL:

```sql
SELECT name FROM skill_index
  WHERE fingerprint = ? AND status = 'active' AND source = ?
    AND owner_user_id IS NULL
  LIMIT 1
```

**调用方约定**:
- `SkillRetrievalHook` 调用 user_generated 路径时,userId 来自 `ctx.getUserId()`,可能为 null(匿名)
- `SkillRetrievalHook` 调用 auto_synthesized 路径时,传 `userId = null`(auto skill 全局共享)
- 因此需要区分"匿名用户查 user_generated"(只看 NULL)和"任何人查 auto_synthesized"(只看 NULL),两者 SQL 相同,语义一致

#### 4.1.4 `topK` 增加重载

```java
/**
 * PR5 - user-scoped L2 top-K. Considers only skills where:
 *   (owner_user_id = userId OR owner_user_id IS NULL) AND source = ?
 */
public List<SkillHit> topK(float[] queryVec, int k, float minCosine, String source, String userId)
```

缓存过滤逻辑:

```java
for (CachedSkill s : cache) {
    if (source != null && !source.equals(s.source())) continue;
    // PR5: user isolation filter
    // userId 非 null: 可见条件 = ownerUserId==null(全局) OR ownerUserId==userId(自己的)
    // userId 为 null: 可见条件 = ownerUserId==null(仅全局)
    if (s.ownerUserId() != null && !s.ownerUserId().equals(userId)) continue;
    // ... cosine compute ...
}
```

**关键**: `ownerUserId == null` 的缓存项(auto / 遗留)对所有用户可见,不过滤。`ownerUserId != null` 的缓存项仅对 owner 可见。

#### 4.1.5 `upsertVector` / `upsertEmbeddingOnly` 的缓存更新

`upsertCacheEntry` 方法需要处理 `ownerUserId` 字段。由于这两个方法只按 name 更新 embedding,不改变 owner_user_id,所以 `lookupSource` 旁边新增 `lookupOwnerUserId`:

```java
private String lookupOwnerUserId(String name) {
    // 先查缓存,再查 DB
}
```

`upsertCacheEntry` 调用时传入 `lookupOwnerUserId(name)`。

### 4.2 `SkillRetrievalHook` 传入 userId

#### 4.2.1 从 RuntimeContext 获取 userId

`SkillRetrievalHook.inject(event, ctx)` 方法中,从 `ctx.getUserId()` 获取当前用户:

```java
private void inject(PreCallEvent event, RuntimeContext ctx) {
    String userId = ctx.getUserId();  // PR5: 用于用户隔离
    // ...
    Optional<String> l1User =
        vectorIndex.findByFingerprint(fingerprint, SkillEntry.SOURCE_USER_GENERATED, userId);
    // ...
    List<SkillVectorIndex.SkillHit> hits =
        vectorIndex.topK(vec, topK, minCosine, SkillEntry.SOURCE_USER_GENERATED, userId);
}
```

auto_synthesized 路径传 `userId = null`(auto skill 全局共享,不需要按用户过滤)。

#### 4.2.2 userId 为空的降级

当 `ctx.getUserId()` 为 null 或空(匿名用户)时:
- user_generated 检索:只匹配 `owner_user_id IS NULL` 的行(遗留全局数据)
- auto_synthesized 检索:正常(本来就全局,也是 `owner_user_id IS NULL`)

这保证了匿名用户不会意外检索到别人的 skill。实现上,`findByFingerprint` 和 `topK` 的 userId 参数为 null 时,SQL/缓存过滤条件统一为 `owner_user_id IS NULL`(详见 §4.1.3 / §4.1.4)。

***

## 5. 创建路径统一

### 5.1 页面创建路径改造(`SkillManageBridge`)

`SkillManageBridge.syncToRetrievalIndex(Skill skill)` 方法中,`indexRepo.upsertOnSave` 需要传入 `owner_user_id`。

#### 5.1.1 `SkillIndexRepository.upsertOnSave` 增加重载

```java
/**
 * PR5 - upsert with owner_user_id for user isolation.
 */
public int upsertOnSave(String name, String description, String source, String ownerUserId) {
    String sql =
        "INSERT INTO skill_index (name, description, version, status, source, owner_user_id)"
        + " VALUES (?, ?, 1, 'active', ?, ?)"
        + " ON DUPLICATE KEY UPDATE"
        + "   description = VALUES(description),"
        + "   version = version + 1,"
        + "   status = 'active',"
        + "   owner_user_id = VALUES(owner_user_id)";
    // ...
}
```

保留原三参数重载(传 `ownerUserId = null`)以兼容 auto_synthesized 调用方。

#### 5.1.2 `SkillManageBridge` 传入 ownerUserId

```java
public String syncToRetrievalIndex(Skill skill) {
    // ...
    int version = indexRepo.upsertOnSave(
        retrievalName, desc, SkillEntry.SOURCE_USER_GENERATED, skill.getOwnerUserId());
    // ...
}
```

### 5.2 Agent 创建路径改造(`SkillSaveTool`)

#### 5.2.1 从 RuntimeContext 获取 userId

`SkillSaveTool` 当前是 Spring 单例 Bean,不持有 `RuntimeContext`。需要让它能获取当前调用的 userId。

**方案**: 让 `SkillSaveTool` 实现 `RuntimeContextAware` 接口(与 `SkillRetrievalHook` 一致):

```java
public class SkillSaveTool implements Tool, RuntimeContextAware {
    private volatile RuntimeContext currentCtx;

    @Override
    public void setRuntimeContext(RuntimeContext context) {
        this.currentCtx = context;
    }
    // ...
}
```

在 `saveSkill` 方法中:

```java
public ToolResultBlock saveSkill(String skillName, String description, String content) {
    String userId = currentCtx != null ? currentCtx.getUserId() : null;
    // ...
    String prefixedName = buildUserScopedName(safeName, userId);
    int version = upsertVersion(prefixedName, desc, userId);
    // ...
    // PR5: 同步写入 skill_manage 表
    syncToSkillManage(prefixedName, desc, body, userId);
}
```

#### 5.2.2 name 前缀

```java
private String buildUserScopedName(String safeName, String userId) {
    if (userId == null || userId.isBlank()) return safeName;  // 匿名降级
    return "usr_" + userId + "_" + safeName;
}
```

#### 5.2.3 写入 `skill_manage` 表

`SkillSaveTool` 新增依赖 `SkillManageService`(通过 ObjectProvider 避免循环依赖):

```java
private void syncToSkillManage(String retrievalName, String description, String content, String userId) {
    if (userId == null || userId.isBlank()) return;  // 匿名不写
    SkillManageService svc = skillManageServiceProvider.getIfAvailable();
    if (svc == null) return;
    try {
        Skill skill = new Skill();
        skill.setName(description);  // skill_manage.name 用 description 作为显示名(中文)
        skill.setDescription(description);
        skill.setContent(content);
        skill.setStatus("ACTIVE");
        skill.setCreatedAt(LocalDateTime.now());
        skill.setUpdatedAt(LocalDateTime.now());
        svc.createForAgent(skill, userId, retrievalName);
    } catch (Exception ex) {
        log.warn("syncToSkillManage failed: {}", ex.getMessage());
    }
}
```

> **name 字段说明**: `skill_manage.name` 是给用户看的显示名(中文/任意字符串),`skill_index.name`(retrievalName)是检索用的 ASCII 标识符。Agent 创建的 skill 没有中文标题,用 description 兜底作为显示名。如果 description 也为空,则用 retrievalName。

`SkillManageService` 新增 `createForAgent` 方法:
- 跳过 `existsByName` 冲突检查(因为 retrievalName 已含 userId 前缀,不会冲突)
- 直接 insert `skill_manage` 表,`retrieval_name = retrievalName`
- 不再调用 `SkillManageBridge.syncToRetrievalIndex`(因为 `SkillSaveTool` 已经写了 `skill_index` + 文件)

#### 5.2.4 `V2ToolConfig` 注入 `SkillManageService` 的 ObjectProvider

```java
@Bean
public SkillSaveTool skillSaveTool(
        @Value("${harness.a2a.workspace.path:.agentscope/workspace/harness-a2a}") String workspacePath,
        SkillIndexRepository indexRepository,
        SkillVectorIndex vectorIndex,
        EmbeddingClient embeddingClient,
        ObjectProvider<SkillManageService> skillManageServiceProvider) {
    Path skillsDir = Paths.get(workspacePath).toAbsolutePath().resolve("skills-user");
    return new SkillSaveTool(skillsDir, indexRepository, vectorIndex, embeddingClient,
            SkillEntry.SOURCE_USER_GENERATED, skillManageServiceProvider);
}
```

### 5.3 Skill 实体补充

`SkillManageService.create()` 方法已有 `ownerUserId` 参数,无需改动。`SkillManageService.createForAgent()` 新增方法见 §5.2.3。

***

## 6. 引用机制改造

### 6.1 当前问题

`SkillManageService.reference(skillId, userId)` 只往 `skill_reference` 表插一行,没有把 skill 内容复制到引用者目录。引用者仍然检索不到被引用的 skill。

### 6.2 改造方案:引用即复制

`reference()` 方法增加复制逻辑:

```java
@Transactional
public void reference(Long skillId, String userId) {
    Skill skill = get(skillId);  // 校验存在
    if (refMapper.existsByCreatorTarget(userId, skillId)) {
        return;  // 幂等
    }
    // 1. 记录引用关系(不变)
    refMapper.insert(SkillReference.builder()
            .sourceSkillId(skillId).targetSkillId(skillId).creator(userId)
            .createdAt(LocalDateTime.now()).build());

    // 2. PR5: 复制 skill 到引用者的检索空间
    if (!userId.equals(skill.getOwnerUserId())) {  // 不复制自己的
        copyToUserRetrievalSpace(skill, userId);
    }
}
```

### 6.3 `copyToUserRetrievalSpace` 实现

```java
private void copyToUserRetrievalSpace(Skill source, String userId) {
    SkillManageBridge bridge = bridgeProvider.getIfAvailable();
    if (bridge == null) return;

    String originalRetrievalName = source.getRetrievalName();  // 如 page_42
    String refRetrievalName = "ref_" + originalRetrievalName + "__u_" + userId;

    try {
        // 1. skill_index 新增一行(owner_user_id = 引用者) + 写 SKILL.md + 异步 embedding
        //    复用 SkillManageBridge 的复制能力,避免 SkillManageService 直接依赖 SkillIndexRepository
        bridge.forkToUserSpace(source, refRetrievalName, userId);
    } catch (Exception ex) {
        log.warn("copyToUserRetrievalSpace failed: {}", ex.getMessage());
    }
}
```

### 6.4 `SkillManageBridge` 新增 `forkToUserSpace` + `copySkillFile` 方法

```java
/**
 * PR5 - 把一个已有 Skill 复制到目标用户的检索空间(引用场景)。
 * 写 skill_index + 复制 SKILL.md + 异步 embedding,三步 best-effort。
 *
 * @param source           源 Skill(从 skill_manage 表读出)
 * @param refRetrievalName 引用副本的检索名(如 ref_page_42__u_user_001)
 * @param userId           引用者 userId
 */
public void forkToUserSpace(Skill source, String refRetrievalName, String userId) {
    String desc = source.getDescription() == null ? "" : source.getDescription();
    String body = source.getContent() == null ? "" : source.getContent();

    // 1. 写 skill_index 表(owner_user_id = 引用者)
    int version = indexRepo.upsertOnSave(refRetrievalName, desc,
        SkillEntry.SOURCE_USER_GENERATED, userId);

    // 2. 复制 SKILL.md 文件(直接用源 content 生成,不依赖原文件存在)
    String frontmatter = SkillSaveTool.renderFrontmatter(refRetrievalName, desc, version);
    String full = frontmatter + body;
    AgentSkill agentSkill = AgentSkill.builder()
            .name(refRetrievalName)
            .description(desc)
            .skillContent(full)
            .source(SkillEntry.SOURCE_USER_GENERATED)
            .build();
    SkillFileSystemHelper.saveSkills(skillsUserDir, List.of(agentSkill), true);

    // 3. 异步计算 embedding
    maybeEmbedAsync(refRetrievalName, desc);
}

/**
 * PR5 - 复制 SKILL.md 文件从源名到目标名(简单文件拷贝,保留供未来需要时使用)。
 */
public void copySkillFile(String sourceName, String targetName) {
    Path sourceDir = skillsUserDir.resolve(sourceName);
    Path targetDir = skillsUserDir.resolve(targetName);
    Path sourceFile = sourceDir.resolve("SKILL.md");
    if (!Files.isRegularFile(sourceFile)) return;
    Files.createDirectories(targetDir);
    Files.copy(sourceFile, targetDir.resolve("SKILL.md"),
        StandardCopyOption.REPLACE_EXISTING);
}
```

### 6.5 取消引用的清理

`unreference()` 方法增加清理逻辑:

```java
@Transactional
public void unreference(Long skillId, String userId) {
    Skill skill = get(skillId);
    refMapper.deleteByCreatorTarget(userId, skillId);

    // PR5: 清理引用副本
    if (!userId.equals(skill.getOwnerUserId())) {
        removeFromUserRetrievalSpace(skill, userId);
    }
}

private void removeFromUserRetrievalSpace(Skill source, String userId) {
    String originalRetrievalName = source.getRetrievalName();
    String refRetrievalName = "ref_" + originalRetrievalName + "__u_" + userId;
    SkillManageBridge bridge = bridgeProvider.getIfAvailable();
    if (bridge != null) {
        bridge.removeFromRetrievalIndex(refRetrievalName);
    }
}
```

### 6.6 引用副本与原 skill 的关系

- 引用副本是**独立副本**,修改原 skill 不影响副本(与 Git fork 语义一致)
- 如果用户想更新副本,取消引用后重新引用即可
- `skill_index` 中副本的 `success_count` / `failure_count` 独立累计

***

## 7. 文件系统影响

### 7.1 目录结构不变

`skills-user/` 目录结构保持不变,仍然是 `skills-user/<retrievalName>/SKILL.md`。新增的命名前缀(`usr_`、`ref_`)只是让目录名更长,不影响结构。

### 7.2 WorkspaceMaterializer 无需改动

`WorkspaceMaterializer` 只负责启动时从 classpath 覆盖 `skills/` 目录,不涉及 `skills-user/`,无需改动。

### 7.3 `SkillRetrievalHook.readSkillBody` 无需改动

`readSkillBody(name, source)` 按 name 解析路径,前缀变化不影响逻辑:

```java
Path p = dir.resolve(name).resolve("SKILL.md");
// name = "usr_user_001_quality_query" -> skills-user/usr_user_001_quality_query/SKILL.md
// name = "ref_page_42__u_user_001" -> skills-user/ref_page_42__u_user_001/SKILL.md
```

***

## 8. 向后兼容

### 8.1 遗留数据处理

- `skill_index` 现有 `user_generated` 行:迁移脚本回填 `owner_user_id`,回填不上的保持 NULL(全局可见)
- `skill_index` 现有 `auto_synthesized` 行:`owner_user_id` 保持 NULL(全局可见)
- `skills-user/` 现有目录:不重命名,对应的 `skill_index` 行 name 不变

### 8.2 API 兼容

- `SkillVectorIndex.findByFingerprint` / `topK`:新重载方法,旧方法保留(传 userId=null 退化为原逻辑)
- `SkillIndexRepository.upsertOnSave`:新重载方法,旧方法保留(传 ownerUserId=null)
- `SkillManageService.reference` / `unreference`:方法签名不变,内部增加复制/清理逻辑

### 8.3 配置开关

新增配置项(默认开启):

```properties
# PR5: Skill 用户隔离
harness.skills.isolation.enabled=true
```

当 `false` 时,检索层不按 userId 过滤(退化为全局共享),创建层不写 owner_user_id。用于灰度发布和紧急回滚。

***

## 9. 涉及改动的文件清单

| 文件 | 改动类型 | 说明 |
|------|---------|------|
| `V20260728.1__skill_index_add_owner_user_id.sql` | 新增 | Flyway 迁移:加列 + 索引 |
| `V20260728.2__skill_index_backfill_owner_user_id.sql` | 新增 | Flyway 迁移:回填 owner_user_id |
| `SkillIndexRepository.java` | 修改 | DDL + ensureTable + upsertOnSave 重载 |
| `SkillVectorIndex.java` | 修改 | CachedSkill + findByFingerprint + topK + 缓存 |
| `SkillRetrievalHook.java` | 修改 | inject 方法传入 userId |
| `SkillSaveTool.java` | 修改 | RuntimeContextAware + name 前缀 + 写 skill_manage |
| `SkillManageBridge.java` | 修改 | 传入 ownerUserId + forkToUserSpace + copySkillFile |
| `SkillManageService.java` | 修改 | reference/unreference 复制逻辑 + createForAgent |
| `V2SkillConfig.java` | 无改动 | Bean 配置不变(SkillManageBridge 构造参数不变) |
| `V2ToolConfig.java` | 修改 | skillSaveTool Bean 注入 ObjectProvider<SkillManageService> |
| `application.properties` | 修改 | 新增 harness.skills.isolation.enabled |

***

## 10. 测试策略

### 10.1 单元测试

- `SkillVectorIndexTest`: 验证 userId 过滤逻辑(cache + DB fallback)
- `SkillSaveToolTest`: 验证 name 前缀 + skill_manage 写入
- `SkillManageServiceTest`: 验证 reference/unreference 复制/清理

### 10.2 集成测试场景

1. **隔离验证**: 用户 A 创建 skill,用户 B 对话检索不到
2. **auto 共享验证**: auto skill 被所有用户检索到
3. **引用验证**: 用户 B 引用 A 的 skill 后,B 能检索到
4. **取消引用验证**: 取消引用后,B 检索不到
5. **Agent 创建验证**: Agent 调用 save_skill 后,管理页面可见
6. **匿名降级验证**: userId 为空时不泄露别人 skill

### 10.3 回归测试

- 现有 `auto_synthesized` 检索路径不受影响(userId=null 退化)
- 现有 `page_<id>` 命名兼容(回填后 owner_user_id 非 NULL)

***

## 11. 风险与缓解

| 风险 | 缓解 |
|------|------|
| 遗留数据回填不完整 | 未回填的行 owner_user_id=NULL,全局可见,不阻断功能 |
| SkillSaveTool 循环依赖 | 用 ObjectProvider 延迟获取 SkillManageService |
| 引用副本导致存储膨胀 | 引用是用户主动行为;后续可加 TTL 清理 |
| name 前缀变长超 128 字符 | userId 限制 64 字符 + safeName 限制 60 字符,前缀余量充足 |
| 并发引用竞态 | refMapper 唯一约束 + copyToUserRetrievalSpace 幂等(upsert + REPLACE_EXISTING) |
