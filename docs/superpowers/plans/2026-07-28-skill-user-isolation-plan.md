# Skill 用户隔离与统一加载实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让每个用户只能检索到自己的 user_generated skill + 全局 auto skill;Agent 调用 save_skill 创建的 skill 也写入 skill_manage 表进入管理页面;引用别人的 skill 后复制到自己的检索空间。

**Architecture:** 在 skill_index 表加 owner_user_id 列,检索层按 userId 过滤(owner_user_id=NULL 表示全局共享)。SkillSaveTool 实现 RuntimeContextAware 拿 userId,写 skill_manage 表。引用时通过 SkillManageBridge.forkToUserSpace 复制 skill 到引用者空间。

**Tech Stack:** Java 17, Spring Boot, MyBatis, MySQL/Flyway, JDBC

## Global Constraints

- 所有代码改动不提交 git(用户要求)
- Java 包路径: `com.agentscopea2a.v2.*`
- MyBatis mapper XML 在 `src/main/resources/mybatis/mapper/mysql/`
- Flyway 迁移在 `src/main/resources/db/migration/`
- skill_index DDL 在 `SkillIndexRepository.java` 的 `DDL` 常量 + `ensureTable()` idempotent ALTER
- 配置文件: `application.properties`(默认值)+ `application-sandbox-linux.properties`
- `skill_index.name` PRIMARY KEY 限 128 字符,需前缀命名避免冲突

**Spec:** `docs/superpowers/specs/2026-07-28-skill-user-isolation-design.md`

---

## File Structure

| 文件 | 责任 |
|------|------|
| `V20260728.1__skill_index_add_owner_user_id.sql` | 新增:Flyway 迁移,加 owner_user_id 列 + 索引 |
| `V20260728.2__skill_index_backfill_owner_user_id.sql` | 新增:Flyway 迁移,回填 owner_user_id |
| `SkillIndexRepository.java` | 修改:DDL + ensureTable + upsertOnSave 重载 |
| `SkillVectorIndex.java` | 修改:CachedSkill + findByFingerprint + topK + 缓存过滤 |
| `SkillRetrievalHook.java` | 修改:inject 方法传入 userId |
| `SkillSaveTool.java` | 修改:RuntimeContextAware + name 前缀 + 写 skill_manage |
| `SkillManageBridge.java` | 修改:传入 ownerUserId + forkToUserSpace |
| `SkillManageService.java` | 修改:reference/unreference 复制逻辑 + createForAgent |
| `V2ToolConfig.java` | 修改:skillSaveTool Bean 注入 ObjectProvider |
| `application.properties` | 修改:新增 harness.skills.isolation.enabled |

---

### Task 1: Flyway 迁移 - skill_index 加 owner_user_id 列

**Files:**
- Create: `analysis-project/src/main/resources/db/migration/V20260728.1__skill_index_add_owner_user_id.sql`
- Create: `analysis-project/src/main/resources/db/migration/V20260728.2__skill_index_backfill_owner_user_id.sql`

**Interfaces:**
- Produces: `skill_index.owner_user_id VARCHAR(64) DEFAULT NULL` 列 + `idx_owner_user_id` 索引

- [ ] **Step 1: 创建加列迁移文件**

创建 `analysis-project/src/main/resources/db/migration/V20260728.1__skill_index_add_owner_user_id.sql`:

```sql
-- PR5: Skill 用户隔离 - 新增 owner_user_id 列
-- NULL = 全局共享(auto_synthesized 或遗留数据)
-- 非 NULL = 仅该用户可检索
ALTER TABLE skill_index
  ADD COLUMN owner_user_id VARCHAR(64) DEFAULT NULL
  COMMENT 'PR5: skill owner for isolation; NULL = global (auto_synthesized or legacy)';

CREATE INDEX idx_owner_user_id ON skill_index(owner_user_id);
```

- [ ] **Step 2: 创建回填迁移文件**

创建 `analysis-project/src/main/resources/db/migration/V20260728.2__skill_index_backfill_owner_user_id.sql`:

```sql
-- 回填现有 page_<id> 行的 owner_user_id
-- 通过 JOIN skill_manage.retrieval_name 关联拿到 owner_user_id
UPDATE skill_index si
  JOIN skill_manage sm ON si.name = sm.retrieval_name
  SET si.owner_user_id = sm.owner_user_id
  WHERE si.source = 'user_generated'
    AND si.owner_user_id IS NULL
    AND sm.owner_user_id IS NOT NULL;
```

- [ ] **Step 3: 验证文件存在**

Run: `dir analysis-project\src\main\resources\db\migration\V20260728.*`
Expected: 两个 .sql 文件

---

### Task 2: SkillIndexRepository - DDL + upsertOnSave 重载

**Files:**
- Modify: `analysis-project/src/main/java/com/agentscopea2a/v2/skills/SkillIndexRepository.java`

**Interfaces:**
- Produces: `upsertOnSave(String name, String description, String source, String ownerUserId)` 重载方法
- Produces: DDL 和 ensureTable 中新增 owner_user_id 列

- [ ] **Step 1: 更新 DDL 常量**

在 `SkillIndexRepository.java` 的 `DDL` 常量中(`source` 列之后),加入 `owner_user_id` 列和索引。

找到 `DDL` 常量中的:
```java
                + "  source VARCHAR(16) NOT NULL DEFAULT 'auto_synthesized'"
                + "      COMMENT 'skill origin: user_generated | auto_synthesized',"
```

在其后面加:
```java
                + "  source VARCHAR(16) NOT NULL DEFAULT 'auto_synthesized'"
                + "      COMMENT 'skill origin: user_generated | auto_synthesized',"
                + "  owner_user_id VARCHAR(64) DEFAULT NULL"
                + "      COMMENT 'PR5: skill owner for isolation; NULL = global (auto_synthesized or legacy)',"
```

并在 DDL 的索引部分(`KEY idx_tool_seq_fp` 之后)加:
```java
                + "  KEY idx_tool_seq_fp (tool_sequence_fingerprint),"
                + "  KEY idx_owner_user_id (owner_user_id)"
```

- [ ] **Step 2: 在 ensureTable() 中加 idempotent ALTER**

找到 `ensureTable()` 方法中最后一个 `try { s.execute("CREATE INDEX idx_source ...") }` 块之后,加:

```java
                try {
                    s.execute("ALTER TABLE skill_index ADD COLUMN owner_user_id VARCHAR(64) DEFAULT NULL COMMENT 'PR5: skill owner for isolation; NULL = global (auto_synthesized or legacy)'");
                } catch (SQLException e) {
                    // Column already exists - ignore
                }
                try {
                    s.execute("CREATE INDEX idx_owner_user_id ON skill_index(owner_user_id)");
                } catch (SQLException e) {
                    // Index already exists - ignore
                }
```

- [ ] **Step 3: 新增 upsertOnSave 四参数重载**

在现有 `upsertOnSave(String name, String description, String source)` 方法之后,新增:

```java
    /**
     * PR5 - upsert with owner_user_id for user isolation.
     *
     * @param ownerUserId nullable - NULL for global (auto_synthesized or legacy);
     *     non-null for user-scoped skills
     * @return the final version after upsert, or -1 when the write failed
     */
    public int upsertOnSave(String name, String description, String source, String ownerUserId) {
        ensureTable();
        String sql =
                "INSERT INTO skill_index (name, description, version, status, source, owner_user_id)"
                        + " VALUES (?, ?, 1, 'active', ?, ?)"
                        + " ON DUPLICATE KEY UPDATE"
                        + "   description = VALUES(description),"
                        + "   version = version + 1,"
                        + "   status = 'active',"
                        + "   owner_user_id = VALUES(owner_user_id)";
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, name);
            ps.setString(2, description == null ? "" : description);
            ps.setString(3, source == null ? SkillEntry.SOURCE_AUTO_SYNTHESIZED : source);
            ps.setString(4, ownerUserId);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warn("upsertOnSave({}, {}, {}, {}) failed: {}", name, description, source, ownerUserId, e.getMessage());
            return -1;
        }
        return findByName(name).map(SkillEntry::version).orElse(-1);
    }
```

- [ ] **Step 4: 确保旧三参数重载调用新重载**

将现有 `upsertOnSave(String name, String description, String source)` 方法体改为委托:

```java
    public int upsertOnSave(String name, String description, String source) {
        return upsertOnSave(name, description, source, null);
    }
```

- [ ] **Step 5: 验证编译**

Run: `cd analysis-project && mvn compile -q -pl . 2>&1 | tail -5`
Expected: BUILD SUCCESS(无编译错误)

---

### Task 3: SkillVectorIndex - CachedSkill + userId 过滤

**Files:**
- Modify: `analysis-project/src/main/java/com/agentscopea2a/v2/skills/SkillVectorIndex.java`

**Interfaces:**
- Consumes: `skill_index.owner_user_id` 列(Task 1)
- Produces: `findByFingerprint(String fingerprint, String source, String userId)` 重载
- Produces: `topK(float[] queryVec, int k, float minCosine, String source, String userId)` 重载

- [ ] **Step 1: CachedSkill record 加 ownerUserId 字段**

找到:
```java
    private record CachedSkill(String name, String description, float[] embedding, float norm, String source) {}
```

改为:
```java
    private record CachedSkill(String name, String description, float[] embedding, float norm, String source, String ownerUserId) {}
```

- [ ] **Step 2: loadAllActiveSkills() 读取 owner_user_id**

找到 `loadAllActiveSkills()` 方法,修改 SQL 和构造:

SQL 从:
```java
        String sql = "SELECT name, description, embedding, source FROM skill_index"
                + " WHERE status = 'active' AND embedding IS NOT NULL";
```
改为:
```java
        String sql = "SELECT name, description, embedding, source, owner_user_id FROM skill_index"
                + " WHERE status = 'active' AND embedding IS NOT NULL";
```

构造 CachedSkill 从:
```java
                list.add(new CachedSkill(
                        rs.getString("name"),
                        rs.getString("description"),
                        vec,
                        n,
                        rs.getString("source")));
```
改为:
```java
                list.add(new CachedSkill(
                        rs.getString("name"),
                        rs.getString("description"),
                        vec,
                        n,
                        rs.getString("source"),
                        rs.getString("owner_user_id")));
```

- [ ] **Step 3: 新增 findByFingerprint 三参数重载(带 userId)**

在现有 `findByFingerprint(String fingerprint, String source)` 方法之后,新增:

```java
    /**
     * PR5 - user-scoped L1 lookup. When {@code userId} is non-null, matches skills where
     * {@code (owner_user_id = userId OR owner_user_id IS NULL)}. When {@code userId} is null,
     * matches only {@code owner_user_id IS NULL} (global skills) - this prevents anonymous
     * users from retrieving other users' skills.
     */
    public Optional<String> findByFingerprint(String fingerprint, String source, String userId) {
        if (fingerprint == null || fingerprint.isBlank()) return Optional.empty();
        String sql;
        if (userId != null && !userId.isBlank()) {
            sql = "SELECT name FROM skill_index"
                    + " WHERE fingerprint = ? AND status = 'active' AND source = ?"
                    + " AND (owner_user_id = ? OR owner_user_id IS NULL) LIMIT 1";
        } else {
            sql = "SELECT name FROM skill_index"
                    + " WHERE fingerprint = ? AND status = 'active' AND source = ?"
                    + " AND owner_user_id IS NULL LIMIT 1";
        }
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, fingerprint);
            ps.setString(2, source);
            if (userId != null && !userId.isBlank()) {
                ps.setString(3, userId);
            }
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Optional.of(rs.getString("name"));
            }
        } catch (SQLException e) {
            log.warn("findByFingerprint({}, {}, {}) failed: {}", fingerprint, source, userId, e.getMessage());
        }
        return Optional.empty();
    }
```

- [ ] **Step 4: 新增 topK 五参数重载(带 userId)**

在现有 `topK(float[] queryVec, int k, float minCosine, String source)` 方法之后,新增:

```java
    /**
     * PR5 - user-scoped L2 top-K. When {@code userId} is non-null, considers only skills where
     * {@code (owner_user_id = userId OR owner_user_id IS NULL)}. When {@code userId} is null,
     * considers only {@code owner_user_id IS NULL} (global skills).
     */
    public List<SkillHit> topK(float[] queryVec, int k, float minCosine, String source, String userId) {
        if (queryVec == null || queryVec.length == 0 || k <= 0) return List.of();
        float qNorm = norm(queryVec);
        if (qNorm == 0f) return List.of();

        List<CachedSkill> cache = this.skillCache;
        List<SkillHit> hits;

        if (cacheEnabled && !cache.isEmpty()) {
            hits = new ArrayList<>();
            for (CachedSkill s : cache) {
                if (source != null && !source.equals(s.source())) continue;
                // PR5: user isolation filter
                // userId 非 null: 可见 = ownerUserId==null(全局) OR ownerUserId==userId(自己的)
                // userId 为 null: 可见 = ownerUserId==null(仅全局)
                if (s.ownerUserId() != null && !s.ownerUserId().equals(userId)) continue;
                if (s.embedding().length != queryVec.length) continue;
                float cos = cosine(queryVec, s.embedding(), qNorm, s.norm());
                if (cos >= minCosine) {
                    hits.add(new SkillHit(s.name(), s.description(), cos));
                }
            }
        } else {
            hits = dbTopK(queryVec, qNorm, minCosine, source, userId);
        }

        hits.sort(Comparator.comparingDouble(SkillHit::cosine).reversed());
        return hits.size() > k ? hits.subList(0, k) : hits;
    }
```

- [ ] **Step 5: 新增 dbTopK 带 userId 的重载**

在现有 `dbTopK(float[] queryVec, float qNorm, float minCosine, String source)` 方法之后,新增:

```java
    /** Full-table SQL scan fallback with user isolation. */
    private List<SkillHit> dbTopK(float[] queryVec, float qNorm, float minCosine, String source, String userId) {
        StringBuilder sql = new StringBuilder("SELECT name, description, embedding FROM skill_index")
                .append(" WHERE status = 'active' AND embedding IS NOT NULL");
        if (source != null) sql.append(" AND source = ?");
        if (userId != null && !userId.isBlank()) {
            sql.append(" AND (owner_user_id = ? OR owner_user_id IS NULL)");
        } else {
            sql.append(" AND owner_user_id IS NULL");
        }
        List<SkillHit> hits = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement(sql.toString())) {
            int idx = 1;
            if (source != null) ps.setString(idx++, source);
            if (userId != null && !userId.isBlank()) ps.setString(idx++, userId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String embeddingJson = rs.getString("embedding");
                    if (embeddingJson == null || embeddingJson.isBlank()) continue;
                    float[] vec;
                    try {
                        vec = MAPPER.readValue(embeddingJson, FLOAT_ARRAY);
                    } catch (Exception ex) {
                        continue;
                    }
                    if (vec.length != queryVec.length) continue;
                    float cos = cosine(queryVec, vec, qNorm, norm(vec));
                    if (cos >= minCosine) {
                        hits.add(new SkillHit(rs.getString("name"), rs.getString("description"), cos));
                    }
                }
            }
        } catch (SQLException e) {
            log.warn("dbTopK(userId={}) failed: {}", userId, e.getMessage());
        }
        return hits;
    }
```

- [ ] **Step 6: 更新 upsertCacheEntry 和 lookupSource 支持 ownerUserId**

找到 `upsertCacheEntry` 方法,修改签名为接收 ownerUserId:

```java
    private synchronized void upsertCacheEntry(String name, float[] embedding, String description, String ownerUserId) {
        float n = norm(embedding);
        if (n == 0f) return;
        String source = lookupSource(name);
        List<CachedSkill> current = new ArrayList<>(this.skillCache);
        current.removeIf(s -> s.name().equals(name));
        current.add(new CachedSkill(name, description, embedding, n, source, ownerUserId));
        this.skillCache = List.copyOf(current);
    }
```

找到 `upsertVector` 方法中的调用:
```java
                upsertCacheEntry(name, embedding, null);
```
改为:
```java
                upsertCacheEntry(name, embedding, null, lookupOwnerUserId(name));
```

找到 `upsertEmbeddingOnly` 方法中的调用:
```java
                upsertCacheEntry(name, embedding, null);
```
改为:
```java
                upsertCacheEntry(name, embedding, null, lookupOwnerUserId(name));
```

在 `lookupSource` 方法之后,新增 `lookupOwnerUserId`:

```java
    /**
     * Best-effort owner_user_id lookup for write-through cache updates.
     */
    private String lookupOwnerUserId(String name) {
        for (CachedSkill s : this.skillCache) {
            if (s.name().equals(name)) return s.ownerUserId();
        }
        String sql = "SELECT owner_user_id FROM skill_index WHERE name = ?";
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getString("owner_user_id");
            }
        } catch (SQLException e) {
            log.debug("lookupOwnerUserId({}) failed: {}", name, e.getMessage());
        }
        return null;
    }
```

- [ ] **Step 7: 验证编译**

Run: `cd analysis-project && mvn compile -q -pl . 2>&1 | tail -5`
Expected: BUILD SUCCESS

---

### Task 4: SkillRetrievalHook - 传入 userId

**Files:**
- Modify: `analysis-project/src/main/java/com/agentscopea2a/v2/hooks/SkillRetrievalHook.java`

**Interfaces:**
- Consumes: `SkillVectorIndex.findByFingerprint(fp, source, userId)` 和 `topK(vec, k, min, source, userId)` (Task 3)

- [ ] **Step 1: inject 方法中获取 userId 并传入检索**

找到 `inject` 方法中的 user_generated 检索部分:

```java
    private void inject(PreCallEvent event, RuntimeContext ctx) {
        String question = ResponseCacheService.extractUserQuestion(event.getInputMessages());
        if (question.isEmpty()) return;

        LinkedHashMap<String, String> picked = new LinkedHashMap<>();
```

改为(在 `picked` 之后加 `userId`):

```java
    private void inject(PreCallEvent event, RuntimeContext ctx) {
        String question = ResponseCacheService.extractUserQuestion(event.getInputMessages());
        if (question.isEmpty()) return;

        String userId = ctx.getUserId();  // PR5: 用于用户隔离

        LinkedHashMap<String, String> picked = new LinkedHashMap<>();
```

- [ ] **Step 2: L1 user 检索传入 userId**

找到:
```java
            Optional<String> l1User =
                    vectorIndex.findByFingerprint(fingerprint, SkillEntry.SOURCE_USER_GENERATED);
```
改为:
```java
            Optional<String> l1User =
                    vectorIndex.findByFingerprint(fingerprint, SkillEntry.SOURCE_USER_GENERATED, userId);
```

- [ ] **Step 3: L2 user 检索传入 userId**

找到:
```java
                List<SkillVectorIndex.SkillHit> hits =
                        vectorIndex.topK(vec, topK, minCosine, SkillEntry.SOURCE_USER_GENERATED);
```
改为:
```java
                List<SkillVectorIndex.SkillHit> hits =
                        vectorIndex.topK(vec, topK, minCosine, SkillEntry.SOURCE_USER_GENERATED, userId);
```

- [ ] **Step 4: L1 auto 检索传 userId=null(auto 全局共享)**

找到:
```java
                Optional<String> l1Auto =
                        vectorIndex.findByFingerprint(fingerprint, SkillEntry.SOURCE_AUTO_SYNTHESIZED);
```
改为:
```java
                Optional<String> l1Auto =
                        vectorIndex.findByFingerprint(fingerprint, SkillEntry.SOURCE_AUTO_SYNTHESIZED, null);
```

- [ ] **Step 5: L2 auto 检索传 userId=null**

找到:
```java
                    List<SkillVectorIndex.SkillHit> hits =
                            vectorIndex.topK(vec, topK, minCosine, SkillEntry.SOURCE_AUTO_SYNTHESIZED);
```
改为:
```java
                    List<SkillVectorIndex.SkillHit> hits =
                            vectorIndex.topK(vec, topK, minCosine, SkillEntry.SOURCE_AUTO_SYNTHESIZED, null);
```

- [ ] **Step 6: 验证编译**

Run: `cd analysis-project && mvn compile -q -pl . 2>&1 | tail -5`
Expected: BUILD SUCCESS

---

### Task 5: SkillManageBridge - 传入 ownerUserId + forkToUserSpace

**Files:**
- Modify: `analysis-project/src/main/java/com/agentscopea2a/v2/skillManager/service/SkillManageBridge.java`

**Interfaces:**
- Consumes: `SkillIndexRepository.upsertOnSave(name, desc, source, ownerUserId)` (Task 2)
- Produces: `forkToUserSpace(Skill source, String refRetrievalName, String userId)` 方法
- Produces: `syncToRetrievalIndex` 传入 ownerUserId

- [ ] **Step 1: syncToRetrievalIndex 传入 ownerUserId**

找到 `syncToRetrievalIndex` 方法中的:
```java
            int version = indexRepo.upsertOnSave(retrievalName, desc, SkillEntry.SOURCE_USER_GENERATED);
```
改为:
```java
            int version = indexRepo.upsertOnSave(retrievalName, desc, SkillEntry.SOURCE_USER_GENERATED, skill.getOwnerUserId());
```

- [ ] **Step 2: 新增 forkToUserSpace 方法**

在 `removeFromRetrievalIndex` 方法之后,新增:

```java
    /**
     * PR5 - 把一个已有 Skill 复制到目标用户的检索空间(引用场景)。
     * 写 skill_index + 写 SKILL.md + 异步 embedding,三步 best-effort。
     *
     * @param source           源 Skill(从 skill_manage 表读出)
     * @param refRetrievalName 引用副本的检索名(如 ref_page_42__u_user_001)
     * @param userId           引用者 userId
     */
    public void forkToUserSpace(Skill source, String refRetrievalName, String userId) {
        if (!enabled || source == null || refRetrievalName == null || userId == null) return;

        String desc = source.getDescription() == null ? "" : source.getDescription();
        String body = source.getContent() == null ? "" : source.getContent();

        try {
            // 1. 写 skill_index 表(owner_user_id = 引用者)
            int version = indexRepo.upsertOnSave(refRetrievalName, desc,
                SkillEntry.SOURCE_USER_GENERATED, userId);

            // 2. 写 SKILL.md 文件(直接用源 content 生成,不依赖原文件存在)
            String frontmatter = SkillSaveTool.renderFrontmatter(refRetrievalName, desc, version);
            String full = frontmatter + body;
            AgentSkill agentSkill = AgentSkill.builder()
                    .name(refRetrievalName)
                    .description(desc)
                    .skillContent(full)
                    .source(SkillEntry.SOURCE_USER_GENERATED)
                    .build();
            boolean saved = SkillFileSystemHelper.saveSkills(skillsUserDir, List.of(agentSkill), true);
            if (!saved) {
                log.warn("Failed to write SKILL.md for forked skill '{}'", refRetrievalName);
                return;
            }

            // 3. 异步计算 embedding
            maybeEmbedAsync(refRetrievalName, desc);

            log.info("Forked skill to user space: {} -> {} (userId={})",
                    source.getName(), refRetrievalName, userId);
        } catch (Exception ex) {
            log.warn("forkToUserSpace failed for '{}' (refRetrievalName={}, userId={}): {}",
                    source.getName(), refRetrievalName, userId, ex.getMessage());
        }
    }
```

- [ ] **Step 3: 验证编译**

Run: `cd analysis-project && mvn compile -q -pl . 2>&1 | tail -5`
Expected: BUILD SUCCESS

---

### Task 6: SkillSaveTool - RuntimeContextAware + name 前缀 + 写 skill_manage

**Files:**
- Modify: `analysis-project/src/main/java/com/agentscopea2a/v2/tools/SkillSaveTool.java`
- Modify: `analysis-project/src/main/java/com/agentscopea2a/v2/config/V2ToolConfig.java`

**Interfaces:**
- Consumes: `SkillManageService.createForAgent(Skill, String, String)` (Task 7)
- Produces: `SkillSaveTool` 实现 `RuntimeContextAware`

- [ ] **Step 1: SkillSaveTool 实现 RuntimeContextAware**

找到类声明:
```java
public class SkillSaveTool {
```
改为:
```java
public class SkillSaveTool implements io.agentscope.core.hook.RuntimeContextAware {
```

在类字段区域(`private final String source;` 之后)加:
```java
    /** PR5: per-call runtime context for userId extraction */
    private volatile RuntimeContext currentCtx;

    /** PR5: ObjectProvider for SkillManageService to avoid circular dependency */
    private final org.springframework.beans.factory.ObjectProvider<com.agentscopea2a.v2.skillManager.service.SkillManageService> skillManageServiceProvider;
```

- [ ] **Step 2: 新增 setRuntimeContext 方法**

在字段之后,新增:

```java
    @Override
    public void setRuntimeContext(RuntimeContext context) {
        this.currentCtx = context;
    }
```

- [ ] **Step 3: 更新所有构造函数**

找到全参构造函数:
```java
    public SkillSaveTool(
            Path skillsDir,
            SkillIndexRepository indexRepository,
            SkillVectorIndex vectorIndex,
            EmbeddingClient embeddingClient,
            String source) {
        this.skillsDir = skillsDir;
        this.indexRepository = indexRepository;
        this.vectorIndex = vectorIndex;
        this.embeddingClient = embeddingClient;
        this.source = source == null ? SkillEntry.SOURCE_AUTO_SYNTHESIZED : source;
    }
```
改为:
```java
    public SkillSaveTool(
            Path skillsDir,
            SkillIndexRepository indexRepository,
            SkillVectorIndex vectorIndex,
            EmbeddingClient embeddingClient,
            String source,
            org.springframework.beans.factory.ObjectProvider<com.agentscopea2a.v2.skillManager.service.SkillManageService> skillManageServiceProvider) {
        this.skillsDir = skillsDir;
        this.indexRepository = indexRepository;
        this.vectorIndex = vectorIndex;
        this.embeddingClient = embeddingClient;
        this.source = source == null ? SkillEntry.SOURCE_AUTO_SYNTHESIZED : source;
        this.skillManageServiceProvider = skillManageServiceProvider;
    }
```

同时更新其他构造函数(两参数和四参数的)委托给全参,传 `null` 给 provider:

```java
    public SkillSaveTool(Path skillsDir, SkillIndexRepository indexRepository) {
        this(skillsDir, indexRepository, null, null, SkillEntry.SOURCE_AUTO_SYNTHESIZED, null);
    }

    public SkillSaveTool(
            Path skillsDir,
            SkillIndexRepository indexRepository,
            SkillVectorIndex vectorIndex,
            EmbeddingClient embeddingClient) {
        this(skillsDir, indexRepository, vectorIndex, embeddingClient, SkillEntry.SOURCE_AUTO_SYNTHESIZED, null);
    }
```

- [ ] **Step 4: saveSkill 方法中加 userId + name 前缀 + 写 skill_manage**

找到 `saveSkill` 方法中的:
```java
        try {
            if (skillName == null || skillName.isBlank()) {
                return ToolResultBlock.error("skill_name 不能为空");
            }
            String safeName = skillName.trim().toLowerCase().replaceAll("[^a-z0-9_]", "_");
            String desc = description == null ? "" : description.trim();
            String body = content == null ? "" : content.trim();
```

改为:
```java
        try {
            if (skillName == null || skillName.isBlank()) {
                return ToolResultBlock.error("skill_name 不能为空");
            }
            String userId = currentCtx != null ? currentCtx.getUserId() : null;
            String safeName = skillName.trim().toLowerCase().replaceAll("[^a-z0-9_]", "_");
            // PR5: 加 userId 前缀避免不同用户同名冲突
            String scopedName = buildUserScopedName(safeName, userId);
            String desc = description == null ? "" : description.trim();
            String body = content == null ? "" : content.trim();
```

找到后续使用 `safeName` 的地方,改为 `scopedName`:

```java
            if (!checkNameAvailable(scopedName)) {
                return ToolResultBlock.error(
                        "技能名 '" + scopedName + "' 已被另一来源占用，请改名后重试");
            }

            int version = upsertVersion(scopedName, desc);
            String frontmatter = renderFrontmatter(scopedName, desc, version);
            String full = frontmatter + body;

            AgentSkill skill =
                    AgentSkill.builder()
                            .name(scopedName)
                            .description(desc)
                            .skillContent(full)
                            .source(source)
                            .build();

            boolean saved = SkillFileSystemHelper.saveSkills(skillsDir, List.of(skill), true);
            if (saved) {
                String msg =
                        "技能保存成功 v"
                                + version
                                + " - "
                                + skillsDir.resolve(scopedName).resolve("SKILL.md");
                log.info("Skill saved: {} v{}", scopedName, version);
                maybeEmbedAsync(scopedName, desc);
                // PR5: 同步写入 skill_manage 表,让管理页面可见
                syncToSkillManage(scopedName, desc, body, userId);
                return ToolResultBlock.text(msg);
            }
```

- [ ] **Step 5: 新增 buildUserScopedName 和 syncToSkillManage 方法**

在 `maybeEmbedAsync` 方法之前,新增:

```java
    /**
     * PR5 - 构建 userId 前缀的检索名,避免不同用户同名冲突。
     * 匿名用户(userId 为空)不加前缀,退化为原有行为。
     */
    private String buildUserScopedName(String safeName, String userId) {
        if (userId == null || userId.isBlank()) return safeName;
        return "usr_" + userId + "_" + safeName;
    }

    /**
     * PR5 - 同步写入 skill_manage 表,让 Agent 创建的 skill 在管理页面可见。
     * best-effort: 失败只 log warn,不影响 skill 保存结果。
     */
    private void syncToSkillManage(String retrievalName, String description, String content, String userId) {
        if (userId == null || userId.isBlank()) return;  // 匿名不写
        if (skillManageServiceProvider == null) return;
        com.agentscopea2a.v2.skillManager.service.SkillManageService svc = skillManageServiceProvider.getIfAvailable();
        if (svc == null) return;
        try {
            Skill skill = new Skill();
            // skill_manage.name 是显示名;Agent 创建的没有中文标题,用 description 兜底
            String displayName = (description != null && !description.isBlank()) ? description : retrievalName;
            skill.setName(displayName);
            skill.setDescription(description);
            skill.setContent(content);
            skill.setStatus("ACTIVE");
            skill.setCreatedAt(java.time.LocalDateTime.now());
            skill.setUpdatedAt(java.time.LocalDateTime.now());
            svc.createForAgent(skill, userId, retrievalName);
        } catch (Exception ex) {
            log.warn("syncToSkillManage failed for '{}': {}", retrievalName, ex.getMessage());
        }
    }
```

- [ ] **Step 6: 更新 saveSkillWithMetricTag 方法**

`saveSkillWithMetricTag` 方法是 auto_synthesized 路径(`SkillSynthesisRunner` 调用),不需要 userId 前缀。但要确保它用旧的 `safeName` 逻辑不变。检查该方法中的 `source` 是否为 `auto_synthesized`--是的,因为 `SkillSynthesisRunner` 用 `SkillEntry.SOURCE_AUTO_SYNTHESIZED` 构造。所以此方法无需改动。

- [ ] **Step 7: 更新 V2ToolConfig 注入 ObjectProvider**

找到 `V2ToolConfig.java` 中的 `skillSaveTool` Bean:

```java
    @Bean
    public SkillSaveTool skillSaveTool(
            @Value("${harness.a2a.workspace.path:.agentscope/workspace/harness-a2a}") String workspacePath,
            SkillIndexRepository indexRepository,
            SkillVectorIndex vectorIndex,
            EmbeddingClient embeddingClient) {
        Path skillsDir = Paths.get(workspacePath).toAbsolutePath().resolve("skills-user");
        log.info("SkillSaveTool: skillsDir={}", skillsDir);
        return new SkillSaveTool(skillsDir, indexRepository, vectorIndex, embeddingClient,
                SkillEntry.SOURCE_USER_GENERATED);
    }
```

改为:

```java
    @Bean
    public SkillSaveTool skillSaveTool(
            @Value("${harness.a2a.workspace.path:.agentscope/workspace/harness-a2a}") String workspacePath,
            SkillIndexRepository indexRepository,
            SkillVectorIndex vectorIndex,
            EmbeddingClient embeddingClient,
            org.springframework.beans.factory.ObjectProvider<com.agentscopea2a.v2.skillManager.service.SkillManageService> skillManageServiceProvider) {
        Path skillsDir = Paths.get(workspacePath).toAbsolutePath().resolve("skills-user");
        log.info("SkillSaveTool: skillsDir={}", skillsDir);
        return new SkillSaveTool(skillsDir, indexRepository, vectorIndex, embeddingClient,
                SkillEntry.SOURCE_USER_GENERATED, skillManageServiceProvider);
    }
```

- [ ] **Step 8: 验证编译**

Run: `cd analysis-project && mvn compile -q -pl . 2>&1 | tail -10`
Expected: BUILD SUCCESS(注意:Task 7 的 createForAgent 还未实现,编译会报错。先做 Task 7 再回来验证)

> **注意**: 此 Task 依赖 Task 7 的 `createForAgent` 方法。两个 Task 需要一起编译验证。

---

### Task 7: SkillManageService - createForAgent + reference/unreference 复制

**Files:**
- Modify: `analysis-project/src/main/java/com/agentscopea2a/v2/skillManager/service/SkillManageService.java`

**Interfaces:**
- Consumes: `SkillManageBridge.forkToUserSpace(Skill, String, String)` (Task 5)
- Produces: `createForAgent(Skill skill, String userId, String retrievalName)` 方法
- Produces: 改造后的 `reference(Long skillId, String userId)` 和 `unreference(Long skillId, String userId)`

- [ ] **Step 1: 新增 createForAgent 方法**

在 `create` 方法之后,新增:

```java
    /**
     * PR5 - Agent 调用 save_skill 创建的 skill 写入 skill_manage 表。
     * 与 {@link #create} 不同:
     * - 跳过 existsByName 冲突检查(retrievalName 已含 userId 前缀)
     * - 不调用 SkillManageBridge.syncToRetrievalIndex(SkillSaveTool 已写 skill_index + 文件)
     * - 直接 insert skill_manage 表,retrieval_name = retrievalName
     *
     * @param skill         Skill 实体(name/description/content/status 已填)
     * @param ownerUserId   所有者 userId
     * @param retrievalName 检索名(如 usr_user_001_quality_query)
     */
    @Transactional
    public void createForAgent(Skill skill, String ownerUserId, String retrievalName) {
        skill.setOwnerUserId(ownerUserId);
        skill.setStatus("ACTIVE");
        skill.setLikeCount(0L);
        skill.setRetrievalName(retrievalName);
        skill.setCreatedAt(LocalDateTime.now());
        skill.setUpdatedAt(LocalDateTime.now());
        skillManageMapper.insert(skill);
    }
```

- [ ] **Step 2: 改造 reference 方法 - 增加复制逻辑**

找到:
```java
    @Transactional
    public void reference(Long skillId, String userId) {
        get(skillId); // 校验 Skill 存在
        if (refMapper.existsByCreatorTarget(userId, skillId)) {
            return; // 幂等
        }
        try {
            refMapper.insert(SkillReference.builder()
                    .sourceSkillId(skillId).targetSkillId(skillId).creator(userId)
                    .createdAt(LocalDateTime.now()).build());
        } catch (DuplicateKeyException e) {
            log.debug("concurrent reference race, idempotent: skill={} user={}", skillId, userId);
        }
    }
```

改为:
```java
    @Transactional
    public void reference(Long skillId, String userId) {
        Skill skill = get(skillId); // 校验 Skill 存在
        if (refMapper.existsByCreatorTarget(userId, skillId)) {
            return; // 幂等
        }
        try {
            refMapper.insert(SkillReference.builder()
                    .sourceSkillId(skillId).targetSkillId(skillId).creator(userId)
                    .createdAt(LocalDateTime.now()).build());
        } catch (DuplicateKeyException e) {
            log.debug("concurrent reference race, idempotent: skill={} user={}", skillId, userId);
        }

        // PR5: 引用即复制 - 把 skill 复制到引用者的检索空间
        if (!userId.equals(skill.getOwnerUserId())) {
            copyToUserRetrievalSpace(skill, userId);
        }
    }
```

- [ ] **Step 3: 新增 copyToUserRetrievalSpace 方法**

在 `reference` 方法之后,新增:

```java
    /**
     * PR5 - 把被引用的 Skill 复制到引用者的检索空间。
     * 通过 SkillManageBridge.forkToUserSpace 完成:写 skill_index + SKILL.md + embedding。
     */
    private void copyToUserRetrievalSpace(Skill source, String userId) {
        SkillManageBridge bridge = bridgeProvider.getIfAvailable();
        if (bridge == null) return;

        String originalRetrievalName = source.getRetrievalName();
        if (originalRetrievalName == null || originalRetrievalName.isBlank()) {
            log.warn("copyToUserRetrievalSpace: source skill {} has no retrievalName, skip", source.getId());
            return;
        }
        String refRetrievalName = "ref_" + originalRetrievalName + "__u_" + userId;

        try {
            bridge.forkToUserSpace(source, refRetrievalName, userId);
        } catch (Exception ex) {
            log.warn("copyToUserRetrievalSpace failed for skill {} user {}: {}",
                    source.getId(), userId, ex.getMessage());
        }
    }
```

- [ ] **Step 4: 改造 unreference 方法 - 增加清理逻辑**

找到:
```java
    @Transactional
    public void unreference(Long skillId, String userId) {
        refMapper.deleteByCreatorTarget(userId, skillId);
    }
```

改为:
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

    /**
     * PR5 - 清理引用者检索空间中的 skill 副本。
     */
    private void removeFromUserRetrievalSpace(Skill source, String userId) {
        String originalRetrievalName = source.getRetrievalName();
        if (originalRetrievalName == null || originalRetrievalName.isBlank()) return;

        String refRetrievalName = "ref_" + originalRetrievalName + "__u_" + userId;
        SkillManageBridge bridge = bridgeProvider.getIfAvailable();
        if (bridge != null) {
            bridge.removeFromRetrievalIndex(refRetrievalName);
        }
    }
```

- [ ] **Step 5: 验证编译(含 Task 6)**

Run: `cd analysis-project && mvn compile -q -pl . 2>&1 | tail -10`
Expected: BUILD SUCCESS

---

### Task 8: application.properties 新增配置开关

**Files:**
- Modify: `analysis-project/src/main/resources/application.properties`

- [ ] **Step 1: 添加配置项**

在 `application.properties` 的 skill 相关配置区域(搜索 `harness.skills`),添加:

```properties

# PR5: Skill 用户隔离
harness.skills.isolation.enabled=true
```

- [ ] **Step 2: 验证配置存在**

Run: `findstr "harness.skills.isolation" analysis-project\src\main\resources\application.properties`
Expected: `harness.skills.isolation.enabled=true`

---

### Task 9: 全量编译验证

**Files:**
- 无新改动,仅验证

- [ ] **Step 1: 全量编译**

Run: `cd analysis-project && mvn compile -q 2>&1 | tail -20`
Expected: BUILD SUCCESS

- [ ] **Step 2: 检查编译警告**

Run: `cd analysis-project && mvn compile 2>&1 | findstr /i "error warning" | head -20`
Expected: 无 error;如有 warning,确认非本次改动引入

---

## Self-Review Checklist

**Spec coverage:**
- [x] §3 数据库 Schema 变更 -> Task 1, Task 2
- [x] §4 检索层改造 -> Task 3, Task 4
- [x] §5 创建路径统一 -> Task 5, Task 6, Task 7
- [x] §6 引用机制改造 -> Task 5 (forkToUserSpace), Task 7 (reference/unreference)
- [x] §7 文件系统影响 -> 无需改动(设计文档已说明)
- [x] §8 向后兼容 -> Task 2 (旧重载保留), Task 8 (配置开关)
- [x] §9 文件清单 -> 全覆盖
- [x] §10 测试策略 -> 编译验证(Task 9),集成测试由用户手动验证

**Placeholder scan:** 无 TBD/TODO,所有步骤都有完整代码。

**Type consistency:**
- `findByFingerprint(String, String, String)` - Task 3 定义,Task 4 调用 ✓
- `topK(float[], int, float, String, String)` - Task 3 定义,Task 4 调用 ✓
- `upsertOnSave(String, String, String, String)` - Task 2 定义,Task 5 调用 ✓
- `forkToUserSpace(Skill, String, String)` - Task 5 定义,Task 7 调用 ✓
- `createForAgent(Skill, String, String)` - Task 7 定义,Task 6 调用 ✓
- `buildUserScopedName(String, String)` - Task 6 定义并调用 ✓
- `syncToSkillManage(String, String, String, String)` - Task 6 定义并调用 ✓
- `CachedSkill` record 6 字段 - Task 3 定义并使用 ✓
