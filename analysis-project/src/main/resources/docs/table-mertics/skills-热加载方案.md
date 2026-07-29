# Skills 热加载方案 v2 (去 DB / 去 embedding)

> 适用目录：`.agentscope/workspace/harness-a2a/skills/`（builtin skills）
> 核心思路：**砍掉 `skill_index` 表 + embedding + `SkillVectorIndex` + `SkillVectorIndexVisibilityFilter` 这整层 dead weight**，让热加载由 JAR 内 `WorkspaceContextHook` 天然提供（每次 session 启动时从磁盘读 `skills/`）。

---

## 1. 背景

当前 builtin skills 走 **DB + vector retrieval + 文件** 三层结构：

```
skills/<name>/SKILL.md (磁盘)
        │
        ├── BuiltinSkillRegistrar (boot 期扫一次, 写 skill_index + 算 embedding)
        │
        ▼
   skill_index 表 (MySQL, 含 embedding 列)
        │
        ├── SkillVectorIndex (60s 周期从 DB 重拉 JVM 缓存)
        │
        ▼
   SkillVectorIndexVisibilityFilter (按 question embedding top-K 过滤)
        │
        ▼
   JAR WorkspaceContextHook 注入 prompt
```

**核心问题**：
1. **retrieval 早已禁用** -- `harness.skills.retrieval.enabled=false`（参见 [[tool_chain_simplification]]），整条 vector 链路其实是 dead code
2. **embedding 不准确** -- `application.properties:125-130` 注释里就写明 `bge-large-zh-v1.5` 中文短句 cosine 偏低（0.55~0.78），实测 R2 极短问句仅 0.57，false-positive 风险大
3. **热加载阻塞** -- `BuiltinSkillRegistrar` 只在 boot 跑一次，新贴 `SKILL.md` 文件没 DB 行就被 visibility filter 挡掉，必须重启 JVM
4. **双写一致性负担** -- `SkillSaveTool` 要同时写文件 + DB + embedding + write-through cache，4 处一致才能保证 retrieval 正确，任一环节挂了就静默漂移

真正的 skill 加载早就不依赖 vector retrieval 了 -- [[plan_mode_disabled_analyze_data]] 后改成纯 ReAct 5 步 workflow，LLM 显式调 `load_skill_through_path` 工具加载具体 skill。vector 那套是历史包袱。

---

## 2. 目标与非目标

### 目标
1. **删除 `skill_index` 表 + 所有 embedding 依赖**，代码与表都清掉
2. **热加载天然生效** -- 往 `skills/` 贴 / 改 / 删 `SKILL.md`，下个 session 启动时 `WorkspaceContextHook` 自动读到，无需任何额外组件
3. **skill 选择由 LLM 显式调 `load_skill_through_path` 完成** -- 与 [[plan_mode_disabled_analyze_data]] 对齐
4. **`SkillSaveTool` 简化为纯文件写入** -- 去掉 DB+embedding，保留 `save_skill` 工具供 LLM 主动合成

### 非目标
- 不改 JAR 内 `WorkspaceContextHook`（影子包，参见 [[shadow_docker_cli_runner_dual_config]]）
- 不解决 `skills-auto/` 和 `skills-user/` 的演化/合成路径（这两个目录的 `SkillSaveTool` 也要同步精简，但合成/演化是否保留见 §7）
- 不动 `FingerprintCalculator`（被 `TraceMiner` 用于 digestion，与 embedding 无关）
- 不引入 `WatchService`（v1 方案的复杂度不需要了）

---

## 3. 删除清单

### 3.1 Java 类（直接删除）

| 类 | 路径 | 删除理由 |
|---|---|---|
| `SkillVectorIndex` | `v2/skills/SkillVectorIndex.java` | 仅服务于已禁用的 retrieval |
| `SkillVectorIndexVisibilityFilter` | `v2/skills/SkillVectorIndexVisibilityFilter.java` | 同上，且会挡掉没 DB 行的新 skill |
| `BuiltinSkillRegistrar` | `v2/skills/BuiltinSkillRegistrar.java` | 仅给 visibility filter 喂 embedding |
| `SkillIndexRepository` | `v2/skills/SkillIndexRepository.java` | `skill_index` 表 DAO，删表就删 DAO |
| `SkillEntry` | `v2/skills/SkillEntry.java` | 表实体类 |
| `EmbeddingClient` | `v2/skills/EmbeddingClient.java` | embedding 接口 |
| `OpenAiCompatEmbeddingClient` | `v2/skills/OpenAiCompatEmbeddingClient.java` | embedding 实现 |

### 3.2 MySQL 表

```sql
DROP TABLE IF EXISTS skill_index;
-- 历史数据不导出,usage_count/failure_count 等统计随表删
-- skill_candidate 表保留(synthesis 用,与 skill_index 解耦)
-- skill_pending_judgement 表保留(evolution 用,与 skill_index 解耦)
```

### 3.3 配置项（从 `application.properties` 删除）

```properties
harness.skills.retrieval.enabled             # 已 false,删
harness.skills.retrieval.top-k               # 删
harness.skills.retrieval.min-cosine          # 删
harness.skills.retrieval.cache-enabled       # 删
harness.skills.retrieval.cache-refresh-seconds  # 删
harness.skills.builtin-registrar.enabled     # 删
harness.skills.embedding.*                  # 删(如有)
```

### 3.4 Spring Bean 注册（从 `V2SkillConfig` / `V2ToolConfig` 删除）

- `skillVectorIndex(...)` bean
- `skillVectorIndexVisibilityFilter(...)` bean
- `builtinSkillRegistrar(...)` bean
- `skillIndexRepository(...)` bean
- `embeddingClient(...)` bean（如有）

---

## 4. 保留清单与精简改造

### 4.1 `SkillSaveTool` -- 精简为纯文件写入

**保留**：
- `@Tool save_skill(...)` 方法签名（LLM 调用契约不变）
- 文件写入逻辑（frontmatter 生成 + body 拼装 + 落盘）
- 异步 `EMBED_EXEC` 线程池删除（不再需要异步算 embedding）

**删除**：
- `SkillIndexRepository indexRepository` 字段及调用
- `SkillVectorIndex vectorIndex` 字段及调用
- `EmbeddingClient embeddingClient` 字段及调用
- `maybeEmbedAsync(...)` 方法

**改造后的伪代码**：
```java
public class SkillSaveTool {
    private final Path skillsDir;
    private final String source;  // user_generated | auto_synthesized,用于决定子目录

    @Tool(name = "save_skill", ...)
    public ToolResultBlock saveSkill(String skillName, String description, String content) {
        Path file = skillsDir.resolve(skillName).resolve("SKILL.md");
        String frontmatter = renderFrontmatter(skillName, description);
        Files.writeString(file, frontmatter + content);
        return ToolResultBlock.success("已保存到 " + file);
    }
}
```

### 4.2 `FingerprintCalculator` -- 保留

被 `TraceMiner`（digestion）用于运行时 fingerprint 计算，**与 embedding / skill_index 无关**，保留不动。

---

## 5. 热加载机制（方案的核心）

**不需要任何额外组件**。热加载由 JAR 内 `HarnessSkillMiddleware` 天然提供，且**只注入 catalogue（name + description），绝不注入 skill body**。

### 5.1 当前 catalogue 注入链路（已存在，不动）

```
agent build (主 agent / 子 agent)
        │
        ▼
HarnessAgent.Builder.build() 装配 HarnessSkillMiddleware
  (传 orderedSkillRepos = [workspace/skills/] + visibilityFilter)
        │
        ▼  每次 LLM call 前
HarnessSkillMiddleware.onSystemPrompt()
        │
        ▼
SkillRuntime.install()
  - 扫 workspace/skills/ 读所有 SKILL.md 的 frontmatter (name + description)
  - 装入 SkillRuntime 的 catalogue (不读 body)
  - idempotently 注册 load_skill_through_path 工具到 agent.getToolkit()
        │
        ▼
LLM 在 system prompt 里看到 catalogue:
  - q2_1_by_dept_version_metrics -- 通过 sql_registry_exec 调预注册 SQL ...
  - wide_table_q2_1_metrics -- ...
  - tool_index -- ...
  - data_primitives -- ...
  - trace_recent_metrics -- ...
        │  (LLM 看不到 body,只看到 name + description)
        ▼
LLM 按需调 load_skill_through_path(name="q2_1_by_dept_version_metrics")
        │
        ▼
SkillLoadTool 读 skills/q2_1_by_dept_version_metrics/SKILL.md 全文返回
        │
        ▼
LLM 在后续 turn 里有了 skill body 的 context
```

**关键事实**（参见 `supervisor-direct-path-design.md:170-181`）：
- `HarnessSkillMiddleware.onSystemPrompt` 每次 LLM call 前都跑（不是只 session 启动跑），catalogue 是**每次 call 重装**
- 装入的是 frontmatter 的 `name + description`，body **从不**进 system prompt
- `load_skill_through_path` 由 JAR `SkillLoadTool` 实现（`io/agentscope/harness/agent/skill/runtime/SkillLoadTool.java:54`），按需读 body

### 5.2 砍掉 visibility filter 后的热加载

当前阻塞热加载的环节是 `SkillVectorIndexVisibilityFilter`（JAR `visibilityFilter` 参数）：
- JAR 装入 catalogue 后调 visibilityFilter 收窄
- filter 用 embedding topK 过滤，没 DB 行的 skill 被挡掉
- 新贴的 SKILL.md 没有 `skill_index` 行 -> 被 filter 挡掉 -> LLM catalogue 里看不到

砍掉 `SkillVectorIndexVisibilityFilter` 后：
- JAR 装 catalogue 后无过滤，**所有 `skills/` 下的 SKILL.md 都进 catalogue**
- 新增 / 修改 / 删除 `SKILL.md` 文件，下次 LLM call（不是下次 session，是下一个 turn）即生效
- 因为 `HarnessSkillMiddleware.onSystemPrompt` 每次 LLM call 都重装 catalogue

### 5.3 生效延迟

- 新文件：下个 LLM call 即生效（通常 < 1s，远比"下个 session"快）
- 修改 frontmatter description：下个 LLM call 即生效
- 修改 body：不影响 catalogue，但下次 `load_skill_through_path` 调用读到新内容
- 删除文件：下个 LLM call catalogue 自动移除，已加载到 LLM context 的 body 不受影响（已在 context 里）

### 5.4 对比 v1 方案

- v1：补 `WatchService` 把文件事件桥到 DB+cache，~200 行新代码
- v2：直接砍掉 DB+cache+visibility filter 这层，0 行新代码（只有删除），热加载天然就有

---

## 6. skill 选择策略（替代 vector retrieval）

### 6.1 默认策略：catalogue 全量注入 + LLM 显式加载 body

JAR `HarnessSkillMiddleware.onSystemPrompt` 在每次 LLM call 前扫 `skills/` 读所有 SKILL.md 的 frontmatter（**仅 name + description，不读 body**），装入 `SkillRuntime` 的 catalogue。LLM 在 system prompt 里看到 catalogue 后，按需调 `load_skill_through_path(name=...)` 工具加载具体 skill 的完整 body。

**关键边界**：
- system prompt 里**只有 catalogue**（name + description，每条约 50~100 字）
- skill body **从不**进 system prompt，只通过 `load_skill_through_path` 按需加载
- 这与用户的"仅有：LLM 看到 5 个 skill 的 name+description"约束严格对齐

**适用场景**：builtin skills 总数 ≤ 20 个，description 简短（< 100 字），catalogue 增量 < 2K tokens。

### 6.2 备选：关键词匹配（如果 catalogue 太大）

在 `HarnessSkillMiddleware` 后挂一个轻量 `SkillKeywordFilter`，按 frontmatter `keywords` 字段（新增）与用户问题做 substring 匹配，只把命中的 skill 留在 catalogue。**不走 embedding**，纯字符串匹配。

**适用场景**：builtin skills 总数 > 20 个，需要预筛 catalogue。

### 6.3 当前 builtin skills 数量

```
.agentscope/workspace/harness-a2a/skills/
├── data_primitives/SKILL.md
├── tool_index/SKILL.md
├── trace_recent_metrics/SKILL.md
├── wide_table_q2_1_metrics/SKILL.md
└── q2_1_by_dept_version_metrics/SKILL.md
```

仅 5 个，**默认策略（catalogue 全量注入）完全够用**，不需要 6.2 的关键词过滤。

---

## 7. 对 synthesis / evolution 的影响 [需用户决策]

砍 `skill_index` 会级联影响 `SkillSynthesisRunner` 和 `SkillEvolutionRunner`，因为它们依赖 `skill_index` 跟踪 usage / failure 计数。三条路：

### 选项 A：一并禁用（推荐）

```properties
harness.skills.auto-synth.enabled=false
harness.skills.evolution.enabled=false
```

- `SkillSynthesisRunner` / `SkillEvolutionRunner` / `SkillSynthesisHook` / `SkillEvolutionHook` 全部可删
- `SkillCandidateRepository` 保留（与 `skill_index` 解耦，表 `skill_candidate` 独立）
- `SkillDistiller` 可删（仅被 synthesis/evolution 调用）

**理由**：
- [[distillation_agent_spawn_confusion]] 记录 qwen3:8b CPU 模式过慢 + LLM 误调 `agent_spawn` 替代 `save_skill`，合成路径本来就有问题
- [[skill_evolution_hook_migrated]] evolution hook 已迁移但实际触发频次低，价值不显著
- 砍掉后整个 v2/skills/ 目录瘦身 ~60%，维护成本大幅下降

### 选项 B：保留，重构为 file-only

- `SkillSynthesisRunner` 触发时不写 `skill_index`，直接调 `SkillSaveTool.saveSkill(...)` 落文件
- `SkillEvolutionRunner` 失败计数改用内存 `ConcurrentHashMap<skillName, AtomicLong>`（JVM 重启丢失，可接受）
- `SkillEvolutionHook` 保留 PreCall/PostCall 钩子，但读改内存计数

**代价**：演化数据不持久化，跨 JVM 不一致；多实例下计数漂移。

### 选项 C：换存储（不推荐）

- 失败计数挪到 `skill_candidate` 表（已有），新增 `failure_count` / `success_count` 列
- 改动面大，得不偿失

**默认采用选项 A**，等业务上确实需要自动合成时再按选项 B 重启。

---

## 8. 实施步骤

1. **配置项禁用**（先关开关，不影响已部署代码）
   ```properties
   harness.skills.auto-synth.enabled=false
   harness.skills.evolution.enabled=false
   ```

2. **删 Java 类**（§3.1 清单）

3. **改 `SkillSaveTool`**：剥离 `indexRepo / vectorIndex / embeddingClient` 字段，保留文件写入

4. **改 `V2SkillConfig` / `V2ToolConfig`**：删除 §3.4 列出的 bean 注册方法

5. **改 `application.properties`**：删除 §3.3 列出的配置项

6. **改其他引用方**：
   - `SubagentRegistrar` 里 `ObjectProvider<SkillSaveTool>` 不动（bean 还在，只是精简了）
   - `SkillFlowEvolver`（digestion 用）需检查是否还引用 `SkillSaveTool` 的 DB/embedding 路径
   - `SkillRetrievalHook` 已被禁用（`retrieval.enabled=false`），可一并删除文件

7. **删表 SQL**（§3.2）

8. **验证**：
   - JVM 启动正常，无 bean 注入失败
   - 用 `q2_1_by_dept_version_metrics` 做基准：复制改名成 `q2_1_copy_test`，下个 LLM call 即在 catalogue 中出现，LLM 可调 `load_skill_through_path(name="q2_1_copy_test")` 加载 body
   - 删除 `q2_1_copy_test/`，下个 LLM call catalogue 自动移除该条

---

## 9. 风险与回退

| 风险 | 缓解 |
|---|---|
| catalogue 体积膨胀（5 条 name+description 约 500 tokens）| 当前仅 5 个 skill，可接受；超过 20 个再上 §6.2 关键词匹配 |
| `SkillSaveTool` 改造影响 `SkillFlowEvolver`（digestion）| 改造前 grep 所有 `new SkillSaveTool(...)` 调用点，确认参数兼容 |
| `SkillCandidateRepository` 表残留无主 | 与 `skill_index` 解耦，`skill_candidate` 表逻辑独立，保留不动 |
| 删表后 DBA 审计需求 | 删表前 `mysqldump` 一次留档，30 天后清理 |
| LLM 选错 skill | 这本来就是 LLM 决策问题，靠 prompt 里 description 写清楚；embedding 反正也不准 |

**回退**：
- 配置层：恢复 `harness.skills.auto-synth.enabled=true` + `harness.skills.evolution.enabled=true` 即可让合成/演化重新生效（前提是 Java 类未删）
- 代码层：git revert
- 数据层：从 `mysqldump` 备份恢复 `skill_index` 表

---

## 10. 与既有记忆的关联

- [[builtin_skill_registrar]] -- **本方案删除该类**，boot 期扫描逻辑不需要了
- [[skill_retrieval_hook_not_migrated]] -- hook 已禁用，本方案直接删代码
- [[skill_synth_hook_migrated]] -- 选项 A 下删除
- [[skill_evolution_hook_migrated]] -- 选项 A 下删除
- [[tool_chain_simplification]] -- retrieval 已禁用，本方案完成代码层清理
- [[plan_mode_disabled_analyze_data]] -- `load_skill` 已是主路径，本方案对齐
- [[workspace_materializer_no_delete]] -- 仍存在，与 `skill_index` 无关，本方案不解决
- [[backend_restart_after_recompile]] -- 本方案上线后"贴新文件 + 重启"痛点解除（无需重启）

---

## 11. v1 vs v2 对比

| 维度 | v1 (WatchService 桥接) | v2 (砍 DB 层) |
|---|---|---|
| 新增代码 | ~200 行 `SkillDirectoryWatcher` | 0 行（纯删除） |
| 依赖 DB+embedding | 保留 | 砍掉 |
| 热加载延迟 | 约 2 秒（watcher 触发） | 下个 LLM call 即生效（毫秒级） |
| prompt 体积 | 不变 | 仅 catalogue（5 条 name+desc 约 500 tokens） |
| 维护成本 | 高（DB+file 双写一致性） | 低（只文件） |
| embedding 不准确问题 | 没解决（仍依赖） | 解决（不再用） |
| 实施风险 | 中（新组件 + 边界情况） | 低（删除 + 验证） |
| 代码净减少 | -50 行（boot 扫描保留） | -1500+ 行（v2/skills/ 大幅瘦身） |

**v2 是更彻底的方案**：承认 vector retrieval 路径已死，完成 [[tool_chain_simplification]] 没做完的清理。
