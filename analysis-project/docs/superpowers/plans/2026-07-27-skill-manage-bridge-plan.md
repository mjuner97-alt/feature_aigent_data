# 页面创建 Skill 双写桥接实施计划

## 背景与根本原因

### 问题现象
通过 Skill 管理页面(`/api/skills`)创建的 Skill 无法被 `/ai/chat` 和 `/V2/ai/chat` 对话接口加载,即使内容正确、状态为 ACTIVE。

### 根本原因
项目存在两套完全独立的 Skill 存储系统,互不知晓:

| 维度 | 对话创建的 Skill | 页面创建的 Skill |
|---|---|---|
| 入口 | `save_skill` 工具 (`SkillSaveTool`) | `SkillManageController` -> `SkillManageService.create()` |
| 落库表 | `skill_index` 表 (`SkillIndexRepository.upsertOnSave`) | `skill_manage` 表 (`SkillManageMapper.insert`) |
| 落盘文件 | `{workspace}/skills-user/{name}/SKILL.md` | 不写文件 |
| Embedding | 异步写入 `skill_index.embedding` | 不写 embedding |
| Fingerprint | 写入 `skill_index.fingerprint` | 不写 fingerprint |

对话加载链路(`SkillRetrievalHook`)只从 `skill_index` 表 + 文件系统读取,**完全没有注入 `SkillManageMapper`/`SkillManageService`**,因此页面创建的 Skill 在检索阶段就找不到:

- 无 `skill_index` 行 -> L1 fingerprint 查询、L2 vector topK 查询都 miss
- 无 `SKILL.md` 文件 -> 即使索引命中,`readSkillBody()` 也返回 null
- 无 embedding -> 无法参与 L2 向量检索

`/ai/chat` 和 `/V2/ai/chat` 使用同一套 `HarnessA2aRunnerV2` + 同一套 hooks,因此两个端点都加载不了页面创建的 skill。

### 对话加载链路(共用)
```
ChatController(/ai/chat) / V2ChatController(/V2/ai/chat)
   -> ChatStreamServiceImpl.stream()  (用 HarnessA2aRunnerV2)
      -> PreCallEvent 触发 SkillRetrievalHook.inject()
         -> L1: SkillVectorIndex.findByFingerprint()  // 查 skill_index 表
         -> L2: SkillVectorIndex.topK()               // 查 skill_index 表的 embedding
         -> 命中后 readSkillBody() 读 {workspace}/skills-user/{name}/SKILL.md
```

## 修复方案: 双写桥接

### 核心思路
在 `SkillManageService` 的写操作里,除了写 `skill_manage` 表,再同步写 `skill_index` 表 + 文件系统 `SKILL.md` + 异步计算 embedding,让页面创建的 Skill 进入检索链路能看到的存储。保留现有检索链路不动,改动最局部。

### 名称映射策略(关键决策)

**问题:**
- `skill_index.name` 是 PRIMARY KEY (VARCHAR 128),要求 `[a-z0-9_]` 格式
- `SkillRetrievalHook.readSkillBody()` 用 name 拼文件路径 `{skillsUserDir}/{name}/SKILL.md`
- 页面 Skill 的 `name` 是中文/任意字符串(如"缺陷密度分析技能"),不能直接用作 `skill_index.name`

**方案:**
- 映射规则: `page_<skillId>`,例如 skill_manage.id=42 -> `page_42`
- 在 `skill_manage` 表新增 `retrieval_name` 列存映射后的名字
- 删除/更新时通过 `retrieval_name` 找到对应的 `skill_index` 行和 `SKILL.md` 文件

**为什么不用 hash(name):**
- `page_<id>` 可读、可逆、不会冲突(id 唯一)
- hash 可能在跨 source 时撞上对话创建的 skill

### 改动文件清单

| 文件 | 类型 | 改动说明 |
|---|---|---|
| `skillManager/service/SkillManageBridge.java` | 新建 | 桥接组件,封装同步逻辑 |
| `skillManager/entity/Skill.java` | 修改 | 加 `retrievalName` 字段 |
| `skillManager/mapper/SkillManageMapper.java` | 修改 | 加 `retrieval_name` 列映射 |
| `mybatis/mapper/mysql/SkillManageMapper.xml` | 修改 | insert/update/select 加 `retrieval_name` 列 |
| `skillManager/service/SkillManageService.java` | 修改 | create/update/delete/approveDraft 调用桥接 |
| `db/migration/V20260727.2__add_retrieval_name_to_skill_manage.sql` | 新建 | DDL |
| `config/V2SkillConfig.java` | 修改 | 注册 `SkillManageBridge` Bean |

## 详细设计

### 1. `SkillManageBridge` 桥接组件

**位置:** `com.agentscopea2a.v2.skillManager.service.SkillManageBridge`

**职责:** 把页面 Skill 同步到检索链路(写 `skill_index` + 写 `SKILL.md` + 异步 embedding)

**依赖:**
- `SkillIndexRepository indexRepo`
- `SkillVectorIndex vectorIndex`
- `EmbeddingClient embeddingClient`
- `Path skillsUserDir` (来自 `${harness.a2a.workspace.path}` + `/skills-user`)

**为什么不直接在 SkillManageService 里加:**
- SkillManageService 已经注入 9 个 mapper,再加 4 个依赖构造函数更臃肿
- `Path skillsUserDir` 来自 V2SkillConfig 的 `@Value`,跨配置类注入不如用独立桥接组件清晰
- 桥接组件可单独开关,便于排障

**核心方法:**

```java
public class SkillManageBridge {
    private static final ScheduledExecutorService EMBED_EXEC = ...;

    /** 同步页面 Skill 到检索索引(skill_index 表 + SKILL.md + embedding) */
    public String syncToRetrievalIndex(Skill skill) {
        if (!enabled) return null;
        String retrievalName = buildRetrievalName(skill.getId());  // "page_<id>"
        String desc = skill.getDescription() == null ? "" : skill.getDescription();
        String body = skill.getContent() == null ? "" : skill.getContent();

        // 1. 检查名称冲突(跨 source)
        if (!indexRepo.checkNameAvailable(retrievalName, SkillEntry.SOURCE_USER_GENERATED)) {
            log.warn("Skill retrieval name '{}' collision, skip sync", retrievalName);
            return retrievalName;
        }

        // 2. 写 skill_index 表(版本自增)
        int version = indexRepo.upsertOnSave(retrievalName, desc, SkillEntry.SOURCE_USER_GENERATED);

        // 3. 写 SKILL.md 文件(复用 SkillSaveTool 的 frontmatter 渲染逻辑)
        String frontmatter = SkillSaveTool.renderFrontmatter(retrievalName, desc, version);
        String full = frontmatter + body;
        AgentSkill agentSkill = AgentSkill.builder()
            .name(retrievalName).description(desc).skillContent(full)
            .source(SkillEntry.SOURCE_USER_GENERATED).build();
        SkillFileSystemHelper.saveSkills(skillsUserDir, List.of(agentSkill), true);

        // 4. 异步计算 embedding
        maybeEmbedAsync(retrievalName, desc);

        return retrievalName;
    }

    /** 从检索索引移除(删 skill_index 行 + 删 SKILL.md 目录) */
    public void removeFromRetrievalIndex(String retrievalName) {
        if (!enabled || retrievalName == null) return;
        // 1. 删 skill_index 行(用 markBlacklist 或新增 delete 方法)
        //    选 markBlacklist 更安全:保留历史计数,可恢复
        indexRepo.markBlacklist(retrievalName);
        // 2. 删 SKILL.md 文件目录
        Path skillDir = skillsUserDir.resolve(retrievalName);
        删除目录(skillDir);
    }

    private String buildRetrievalName(Long skillId) {
        return "page_" + skillId;
    }

    private void maybeEmbedAsync(String name, String description) {
        // 复用 SkillSaveTool 的逻辑
        final String text = (name + " " + description).trim();
        if (text.isEmpty()) return;
        EMBED_EXEC.submit(() -> {
            float[] vec = embeddingClient.embed(text);
            if (vec != null) vectorIndex.upsertVector(name, null, vec);
        });
    }
}
```

### 2. `Skill` 实体加字段

```java
// Skill.java
private String retrievalName;  // 映射到 skill_index.name 的检索名,格式 page_<id>
```

### 3. Mapper XML 改动

```xml
<!-- resultMap 加 -->
<result column="retrieval_name" property="retrievalName"/>

<!-- cols sql 片段加 retrieval_name -->
<sql id="cols">id, name, description, content, category, tags, owner_user_id, status, like_count, created_at, updated_at, deleted_at, retrieval_name</sql>

<!-- insert 加 retrieval_name -->
<insert id="insert" ...>
    INSERT INTO skill_manage (name, description, content, category, tags, owner_user_id, status, like_count, retrieval_name)
    VALUES (#{name}, #{description}, #{content}, #{category}, #{tags}, #{ownerUserId}, #{status}, 0, #{retrievalName})
</insert>

<!-- update 加 retrieval_name -->
<update id="update" ...>
    UPDATE skill_manage
    SET name=#{name}, description=#{description}, content=#{content},
        category=#{category}, tags=#{tags}, status=#{status}, retrieval_name=#{retrievalName}
    WHERE id = #{id}
</update>
```

### 4. `SkillManageService` 调用桥接

**注入方式:** 用 `ObjectProvider<SkillManageBridge>` 避免启动顺序问题

```java
public class SkillManageService {
    private final ObjectProvider<SkillManageBridge> bridgeProvider;

    @Transactional
    public Skill create(Skill skill, String ownerUserId) {
        // ... 原有逻辑 ...
        skillManageMapper.insert(skill);
        Skill saved = skillManageMapper.selectById(skill.getId());

        // 桥接同步到检索索引
        SkillManageBridge bridge = bridgeProvider.getIfAvailable();
        if (bridge != null) {
            String retrievalName = bridge.syncToRetrievalIndex(saved);
            saved.setRetrievalName(retrievalName);
            skillManageMapper.update(saved);  // 回填 retrieval_name
        }
        return saved;
    }

    @Transactional
    public Skill update(Long id, Skill patch, String userId) {
        // ... 原有逻辑 ...
        skillManageMapper.update(s);
        Skill updated = skillManageMapper.selectById(id);

        SkillManageBridge bridge = bridgeProvider.getIfAvailable();
        if (bridge != null) {
            bridge.syncToRetrievalIndex(updated);  // 覆盖 SKILL.md + 更新 skill_index
        }
        return updated;
    }

    @Transactional
    public void delete(Long id, String userId) {
        Skill s = get(id);
        // ... 原有逻辑 ...
        skillManageMapper.softDelete(id);

        SkillManageBridge bridge = bridgeProvider.getIfAvailable();
        if (bridge != null) {
            bridge.removeFromRetrievalIndex(s.getRetrievalName());
        }
    }

    @Transactional
    public void approveDraft(Long draftId, String approverId, String comment) {
        // ... 原有逻辑 ...
        skillManageMapper.update(updated);

        SkillManageBridge bridge = bridgeProvider.getIfAvailable();
        if (bridge != null) {
            bridge.syncToRetrievalIndex(updated);
        }
    }
}
```

### 5. 数据库迁移

```sql
-- V20260727.2__add_retrieval_name_to_skill_manage.sql
ALTER TABLE skill_manage ADD COLUMN retrieval_name VARCHAR(128) NULL 
  COMMENT '映射到 skill_index.name 的检索名,page_<id> 格式';
CREATE INDEX idx_retrieval_name ON skill_manage(retrieval_name);
```

### 6. V2SkillConfig 注册 Bean

```java
@Bean
public SkillManageBridge skillManageBridge(
        SkillIndexRepository indexRepo,
        SkillVectorIndex vectorIndex,
        EmbeddingClient embeddingClient,
        @Value("${harness.a2a.workspace.path:.agentscope/workspace/harness-a2a}") String workspacePath,
        @Value("${harness.skills.page-bridge.enabled:true}") boolean enabled) {
    Path skillsUserDir = Paths.get(workspacePath).toAbsolutePath().resolve("skills-user");
    log.info("SkillManageBridge: enabled={}, skillsUserDir={}", enabled, skillsUserDir);
    return new SkillManageBridge(indexRepo, vectorIndex, embeddingClient, skillsUserDir, enabled);
}
```

## 配置项

| 配置项 | 默认值 | 说明 |
|---|---|---|
| `harness.skills.page-bridge.enabled` | `true` | 是否启用页面 Skill 到检索索引的同步 |

## 风险点与应对

### 1. 名称冲突
- 风险: 页面 Skill 的 `page_<id>` 与对话创建的 skill name 撞
- 应对: `page_` 前缀 + `checkNameAvailable` 检查,冲突时只 log warn 跳过,不影响 skill_manage 写入

### 2. Embedding 服务可用性
- 风险: embedding 服务挂了导致同步失败
- 应对: 保留 `SkillVectorIndex` 现有 best-effort 语义,失败只 log warn,不阻断主流程

### 3. 事务边界
- 风险: 文件写入和 embedding 不在 `@Transactional` 内(文件系统不支持事务),可能出现 skill_manage 写成功但文件/embedding 失败
- 应对: 接受最终一致性。后续可选加补偿任务(扫描 skill_manage 有 retrieval_name 但 skill_index 无对应行的记录)

### 4. 草稿审批流的影响
- `approveDraft()` 走变更草稿流程,草稿 PENDING 期间主表内容未变,无需同步
- 仅在 `approveDraft()` 通过后同步一次即可

## 验证清单

- [ ] 页面创建 Skill 后,`skill_index` 表有对应 `page_<id>` 行,source=`user_generated`
- [ ] 页面创建 Skill 后,`{workspace}/skills-user/page_<id>/SKILL.md` 文件存在
- [ ] 页面创建 Skill 后,`skill_index.embedding` 列在异步任务完成后非空
- [ ] 页面更新 Skill 内容后,`SKILL.md` 文件内容同步更新
- [ ] 页面删除 Skill 后,`skill_index` 对应行 status 变为 `blacklist`,`SKILL.md` 文件删除
- [ ] 草稿审批通过后,`SKILL.md` 文件内容同步更新
- [ ] `/ai/chat` 对话能命中页面创建的 Skill(L2 vector 检索)
- [ ] `/V2/ai/chat` 对话能命中页面创建的 Skill
- [ ] `harness.skills.page-bridge.enabled=false` 时,页面 Skill 不同步但 CRUD 正常

## 不在本次范围

- 统一 `skill_manage` 和 `skill_index` 两张表(方案3,改动太大)
- 改造 `SkillRetrievalHook` 直接查 `skill_manage` 表(方案2,需要解决 embedding 缺失)
- 历史数据补同步(已存在的页面 Skill 需要手动跑一次同步脚本,或后续单独处理)
- 补偿任务实现(最终一致性兜底,后续迭代)
