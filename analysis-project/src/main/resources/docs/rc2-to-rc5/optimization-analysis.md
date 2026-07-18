# 项目优化分析报告

> 日期：2026-07-18
> 分支：`upgrade/2.0.0-RC5-dual-track`
> 范围：v2 主链路 + 配置 + 测试 + 治理
> 目的：识别可优化点，给出按优先级排序的改进路径
> 修订：v2 - 结合 3 轮代码深度分析补充 P0-5 / P1-2 系统化 / P1-7 / P2-5 ~ P2-8

---

## 一、项目现状快照

| 维度 | 现状 |
|---|---|
| 主代码 | 111 个 `*.java`（v2 占绝大多数，v1 已 Maven-exclude 但源码仍在树） |
| 测试代码 | 12 个 `*.java`，**全部在 v1 路径**（`com.agentscopea2a.agent.*` / `org.example.harness.a2a.*`） |
| 框架 | Spring Boot 3.2.3 + agentscope 2.0.0-RC5 + Java 17（pom 声明，实际跑 JDK 21） |
| 配置 | 5 个 `application-*.properties`（dev / prod / sandbox-windows / sandbox-linux / sandbox-linux-remote） |
| 数据源 | 3 套（MySQL + ClickHouse + openGauss），手动 `@MapperScan` + `SqlSessionFactory` |
| Schema 管理 | **无 Flyway/Liquibase**，靠手动 SQL |
| 监控 | `SimpleMeterRegistry`（in-memory），**无 actuator / Prometheus exporter** |
| 事务管理 | **`@Transactional` 0 命中**（v2 下搜不到，只在 datasource config 注释里出现） |
| 工作区状态 | 175 个未提交改动（CURRENT-STATUS.md §4.3 已记录） |
| 最近 3 commit | `fa61d9d` userId 兼容 / `d0bec3b` 短链+降级+心算 / `a732dec` 框架升级基本完成 |

---

## 二、可优化点（按优先级）

### P0 - 阻塞生产部署

#### P0-1 数据库 Schema 无版本管理

**问题**：项目无 `src/main/resources/db/migration/` 目录，schema 靠 `ssh root@... "docker exec mymysql mysql -e 'CREATE TABLE...'"` 手动执行。已经在 `interactive-pause-resume-plan.md` 和 `url_shortener` 等场景多次手动建表。

**风险**：
- 多环境（dev/sandbox/prod）schema 漂移
- 重装/迁移时遗漏表导致启动失败
- 没有 rollback 路径

**建议**：引入 Flyway，把现有 schema 写成 baseline 迁移：
- `V20260718__baseline_agentscope_sessions.sql`（agent_state / agentscope_sessions）
- `V20260718__baseline_episodic_memory.sql`（episodic_memory / agent_memory / agent_memory_ledger）
- `V20260718__baseline_skill_index.sql`（skill_index / skill_candidate）
- `V20260718__baseline_url_shortener.sql`
- `V20260718__baseline_response_cache.sql`
- `V20260718__baseline_skill_usage.sql`

#### P0-2 工作区 175 个未提交改动

**问题**：`git status` 显示 Stage 5/8 迁移、E2E 修复、SubagentRegistrar 等大量改动未 commit。CURRENT-STATUS.md §4.3 已明确标注，但最近 3 commit 停在 Stage 7。

**风险**：
- 代码丢失风险（开发机故障）
- Code review 缺失
- 后续协作困难

**建议**：按主题分批 commit：
1. Stage 5 补齐（ArtifactSweeper / SshHealthCheck）
2. Stage 8 全量迁移（digestion / MemoryLedgerMirror）
3. E2E 修复（4 bug + 3 配置错误）
4. SubagentRegistrar + hook 链对齐
5. 短链 + 模型降级 + ArithTool

#### P0-3 v1 代码树未清理

**问题**：`src/main/java/com/agentscopea2a/{agent,harness,service}/**` 已被 Maven exclude 不编译，但源码仍在树。`git status` 显示 30+ 个 `D` 标记的 v1 文件未实际删除。

**风险**：
- 误导开发者（看到 v1 类以为还能用）
- IDE 索引负担
- 后续 merge 冲突

**建议**：在 Stage 9 一次性删除 v1 代码树（CURRENT-STATUS.md §5.1 已列入待办）。

#### P0-4 无 actuator + Prometheus

**问题**：`pom.xml` 未引入 `spring-boot-starter-actuator`，`V2InfraConfig` 手动 `new SimpleMeterRegistry()` 是 in-memory，无法被 Prometheus 抓取。`stage1-8-e2e-test-report.md` 第 17 行明确指出这点。

**风险**：
- 生产环境无监控指标
- LLM 调用延迟 / 工具失败率 / sandbox 等待时间都看不到
- 故障排查只能靠日志

**建议**：
- 加 `spring-boot-starter-actuator` + `micrometer-registry-prometheus`
- `application.properties` 开 `management.endpoints.web.exposure.include=health,info,metrics,prometheus`
- 给关键路径加 `@Timed`：`V2ChatStreamServiceImpl.stream` / `PythonExecTool.pythonExec` / `ToolRoutersIndex.call` / `FallbackModelDecorator.stream`

#### P0-5 python_exec 无跨租户防护（PythonExecAccessHook 缺失）

**问题**（v2 新增）：`PythonExecTool.java:331-336` 的 `denyIfObviouslyCrossBucket` 方法是 **空实现**，注释写"leave actual enforcement to PythonExecAccessHook (P0-D)"，但 **`PythonExecAccessHook` 在整个 v2 代码树里不存在**。`PythonExecTool.java:73` 和 `:137` 的注释也引用了这个不存在的 hook。

同时 `ArtifactAccessMiddleware.java:60-62` 的拦截列表只包含 `READ_FILE_TOOL` / `WRITE_FILE_TOOL` / `SHELL_EXECUTE_TOOL`，**`python_exec` 不在拦截列表**。

**风险**：
- 恶意 LLM 可在 `python_exec` 里 `pd.read_csv('../other_user/artifact.csv')` 读其他用户 artifact
- 共享容器（`agentscope-shared-demo`）场景下，所有用户的 bind mount 都在同一文件系统
- `denyIfObviouslyCrossBucket` 空方法 + 无 hook = 零防护

**建议**：
- 实现 `PythonExecAccessHook`（`RuntimeContextAware` + `Hook<PreToolUse, PostToolUse>`），在 PreToolUse 拦截 `python_exec` 调用，解析 code 字符串里的路径引用，校验是否落在 `RuntimeContext` 的 artifact 根下
- 或在 `PythonExecTool.pythonExec` 入口直接校验 `RuntimeContext.current().artifactBase()`，拒绝越界路径
- 在 `V2InfraConfig` 装配这个 hook（对齐 `ArtifactHandoffHook` 的装配方式）
- 加 E2E 用例：构造 python_exec payload 读 `../other-user/` 路径，验证被拒绝

---

### P1 - 架构 / 可维护性

#### P1-1 HarnessA2aRunnerV2 构造器爆炸

**问题**：`HarnessA2aRunnerV2`（`src/main/java/com/agentscopea2a/v2/runner/HarnessA2aRunnerV2.java:85-118`）构造器有 **30+ 参数**：11 个 `@Value` + 9 个 `ObjectProvider` + 7 个直接 bean 依赖。340 行的类承担了"装配 agent + 装配 middleware + 装配 hooks + 装配 toolkit + 装配 subagents + 装配 async bus"6 件事。

**反模式**：上帝类 + 字段注入式构造器。

**建议**：拆成多个 @ConfigurationProperties + 装配类：
- `ModelProperties`（已存在 `ModelProperties` 但没被 runner 用） -> 替代 11 个 `@Value`
- `MiddlewareConfig` 装配 5 个 middleware（responseCache / dimension / episodic / artifact / session）
- `HookConfig` 装配 7 个 hook（handoff / retry / tracking / retrieval / synthesis / evolution / knowledge）
- `RunnerBuilder` 把上面的组装成 `HarnessAgent`

#### P1-2 单例 hook 实例字段多用户串扰（系统化问题）

**问题**（v2 修订 - 从 DimensionStateManager 单点扩展为系统性问题）：v1 版报告只列了 `DimensionStateManager`，3 轮深度代码审查发现 **5 个 `RuntimeContextAware` 单例 hook 全部** 用 `private volatile RuntimeContext currentCtx` 实例字段缓存 per-request 上下文，存在多用户竞态：

| 类 | 行号 | 实例字段 | 风险 |
|---|---|---|---|
| `v2/dimension/DimensionStateManager.java` | (798 行类) | `private DimensionState currentState` | 用户 A 的"杭州开发一部"被用户 B 的"北京"覆盖（[[dimension_state_persistence_gap]]） |
| `v2/hooks/ArtifactHandoffHook.java` | `:73` | `private volatile RuntimeContext currentCtx` | 用户 A 的 artifact 路径被注入到用户 B 的 CSV 引用 |
| `v2/hooks/SkillEvolutionHook.java` | `:94` | `private volatile RuntimeContext currentCtx` | 用户 A 的失败反馈被记到用户 B 的 skill ledger |
| `v2/hooks/SkillEvolutionHook.java` | `:92` | `private List<String> currentTurnRetrieved` | 用户 A 检索到的 skill 列表泄漏给用户 B |
| `v2/hooks/SkillRetrievalHook.java` | `:83` | `private volatile RuntimeContext currentCtx` | 用户 A 的 episodic 查询命中用户 B 的记忆 |
| `v2/hooks/SkillSynthesisHook.java` | `:56` | `private volatile RuntimeContext currentCtx` | 用户 A 的 cache-MISS 触发蒸馏写入用户 B 的 candidate 行 |
| `v2/hooks/ToolCallTrackingHook.java` | `:64` | `private volatile RuntimeContext currentCtx` | 用户 A 的 tool call 统计混入用户 B 的 trace |

**根因**：框架要求 hook 是单例 bean（在 `HarnessAgent.Builder` 上 `.hook()` 装配一次），但 `RuntimeContextAware` 的 `withContext(ctx)` 回调把 ctx 写进了实例字段而不是 ThreadLocal。同一 bean 被多个并发请求共享时，后到的请求覆盖先到的 `currentCtx`，先到的请求读到的就是后到用户的上下文。

**为什么测试没抓到**：E2E 测试串行执行，单线程下 `volatile` 字段写后立即读不会出问题。只有并发请求 + LLM 调用期间（hook 中的 LLM 调用让出 CPU 时间片）才会触发。

**建议**：
- **方案 A（推荐）**：把 `currentCtx` 改成 `ThreadLocal<RuntimeContext>` 或 `RuntimeContext.current()`（框架提供的 ScopedValue 风格 API），让每个请求线程独立持有 ctx
- **方案 B**：把 `currentCtx` 从实例字段改成方法参数 - 重构所有 hook 方法签名，让框架在 `onPreCall(ctx, ...)` / `onPostCall(ctx, ...)` 时把 ctx 直接传进来（需要框架配合）
- **方案 C（兜底）**：在每个 hook 的 `onPreCall` 入口同步 `currentCtx = ctx`，并在所有使用 `currentCtx` 的地方同步到 `RuntimeContext.current()` 读取，配合 `synchronized` 块 - 但这等于退化为串行
- 优先级：`DimensionStateManager` > `ArtifactHandoffHook` > `SkillEvolutionHook` > 其他

#### P1-3 v2 测试覆盖严重不足

**问题**：12 个测试文件全部在 v1 路径，v2 几乎无单元测试。`HarnessA2aRunnerV2` / `V2ChatStreamServiceImpl` / `V2ToolGroupAdapter` / `SubagentRegistrar` / 所有 hooks / 所有 middleware 都没测试。

**风险**：
- 重构时容易引入回归
- E2E 测试发现 bug 的成本远高于单元测试
- 框架升级（RC5 -> GA）时无安全网
- **P1-2 的多用户串扰 bug 如果有并发单元测试本该早发现**

**建议**：优先补：
- `FallbackModelDecorator` 单元测试（auth error / 5xx / timeout / retry exhausted 4 个路径）
- `V2ToolGroupAdapter` 单元测试（group 切换 / meta-tool 调用）
- `ToolRoutersIndex` 单元测试（toolId 路由 / 参数反序列化 / 异常传播）
- `DimensionStateManager` **并发单元测试**（CountDownLatch + 2 线程模拟 2 user 同时 chat，验证 state 不串）
- 5 个 `RuntimeContextAware` hook 各自的并发单元测试（同上模式）
- `SkillRetrievalHook` / `SkillEvolutionHook` 单元测试（PR3 / PR4 路径）

#### P1-4 异常处理吞异常

**问题**（v2 修订 - 量化）：搜索 `catch.*Exception.*log\.(warn|error)` 在 v2 下大量命中，深度统计：
- `V2ChatStreamServiceImpl` - 至少 5 处 `catch (Exception ex) { log.warn(...) }` 不抛出
- `TraceMiner` - 9 处 catch，多数只 log
- `MySqlEpisodicMemory` - 12 处 catch，含 SQL 写入失败时静默
- `ToolRoutersIndex.call` - 把所有异常包成 `IllegalArgumentException` / `IllegalStateException`（详见 P2-8）

**风险**：
- 故障被静默吞掉
- 上层不知道下游失败
- 日志里能看到 warn 但调用方拿到的是空响应
- `MySqlEpisodicMemory` 写入失败静默 -> 记忆丢失，用户感觉"agent 忘了"但无错误信号

**建议**：
- 区分"可恢复异常"（log + 兜底返回）和"应抛出异常"（log + rethrow）
- 引入自定义异常体系（项目目前只有 2 个：`DimensionException` + `V1RoutingNotAvailableException`）
- 至少加 `ToolExecutionException` / `SkillDistillationException` / `SandboxUnavailableException` / `MemoryPersistenceException`
- `MySqlEpisodicMemory` 的写入失败必须 rethrow（记忆丢失比抛异常更糟）

#### P1-5 V2ChatStreamServiceImpl 每请求 new SingleThreadExecutor + 不 shutdown

**问题**（v2 修订 - 补充 shutdown 缺失）：`V2ChatStreamServiceImpl.java:173` 每次请求 `Executors.newSingleThreadExecutor().submit(...)`，但 **emitter 完成后没有 `executor.shutdown()` 调用**（grep 全文件无 shutdown）。

**风险**：
- 高并发下线程数无界增长（虽然单线程 executor 完成后会 idle，但不会自动销毁）
- 长时间运行后线程数爆炸
- JVM 退出时非 daemon 线程阻止退出

**建议**：
- 改用 Spring 管理的 `@Bean ThreadPoolTaskExecutor`，请求只 submit 不创建
- 或用 Reactor 的 `Schedulers.boundedElastic()` 替代手动 executor
- 立即修复：在 emitter 的 `onCompletion` / `onTimeout` / `onError` 回调里加 `executor.shutdown()`

#### P1-6 AgentscopeA2aApplication 排除 MySqlEpisodicMemory 但 V2MemoryConfig 又装配

**问题**：`AgentscopeA2aApplication.java:11-15` 用 `@ComponentScan(excludeFilters = ...)` 排除 `MySqlEpisodicMemory`，但 `V2MemoryConfig` 又 `@Bean` 显式装配它。

**风险**：治理混乱 - 后续维护者不清楚到底是排除还是装配。当前能跑只是因为 excludeFilters 和 @Bean 两条路径恰好不冲突。

**建议**：
- 把 `MySqlEpisodicMemory` 改成不被 `@ComponentScan` 扫描（移到 `v2/memory/internal/` 包，或去掉 `@Component`）
- 删除 `AgentscopeA2aApplication` 的 excludeFilters
- 让 `V2MemoryConfig` 成为唯一的装配源

#### P1-7 @Transactional 完全未使用

**问题**（v2 新增）：grep `@Transactional` 在 `src/main/java/com/agentscopea2a/v2/**` 下 **0 命中**（只在 datasource config 文件的注释里出现）。所有 MySQL 写入都缺事务边界：

| 写入点 | 现状 | 风险 |
|---|---|---|
| `SkillIndexRepository.insertCandidate` | raw JDBC，无事务 | candidate 行写一半失败留 dirty 行 |
| `SkillIndexRepository.updateSkillStatus` | raw JDBC，无事务 | 状态机更新失败导致 candidate 卡在中间态 |
| `MySqlEpisodicMemory.write` | 多条 SQL（ledger + memory + index），无事务 | 部分写入失败导致 ledger 与 memory 不一致 |
| `TraceMiner.writeLedger` | raw JDBC，无事务 | trace 行写一半失败 |
| `UrlShortenerService.create` | Mapper 调用，无事务 | 短链生成并发冲突时无 rollback |
| `MysqlAgentStateStore.saveState` | JSON 序列化 + UPDATE，无事务 | JVM crash 中途留下半截 state |

**根因**：项目 3 套数据源（MySQL + ClickHouse + openGauss）用手动 `@MapperScan` + `SqlSessionFactory` 装配，没引入 `DataSourceTransactionManager`，所以 `@Transactional` 即使加了也不生效。

**建议**：
- 给 MySQL 数据源配 `DataSourceTransactionManager` bean
- 关键写入点加 `@Transactional`：`SkillIndexRepository.insertCandidate` / `MySqlEpisodicMemory.write` / `UrlShortenerService.create`
- `TraceMiner` 的 batch-scan 写入改成单事务批量 INSERT
- 单元测试用 `@Transactional(rollback=true)` 隔离

---

### P2 - 代码质量

#### P2-1 超长类

| 文件 | 行数 | 问题 |
|---|---|---|
| `v2/digestion/TraceMiner.java` | 874 | 多职责（trace 加载 + 用户聚合 + 模式挖掘 + skill 演化触发） |
| `v2/dimension/DimensionStateManager.java` | 798 | 状态机 + LLM 调用 + 持久化混在一起 |
| `v2/skills/SkillDistiller.java` | 650 | prompt 模板 + LLM 调用 + SKILL.md 解析 + 落盘 |
| `v2/skills/SkillSynthesisRunner.java` | 634 | 异步调度 + subagent 调用 + 失败重试 + candidate 状态机 |
| `v2/memory/MySqlEpisodicMemory.java` | 622 | SQL + embedding + 检索 + 写入混在一起 |

**建议**：每个超长类按职责拆 2-4 个类。例如 `TraceMiner` 拆成 `TraceLoader`（SQL） + `UserTraceAggregator` + `PatternMiner` + `SkillEvolutionTrigger`。

#### P2-4 5 个 application-*.properties profile 切换易错

**问题**：dev / prod / sandbox-windows / sandbox-linux / sandbox-linux-remote 5 个 profile，每个都重复 datasource / sandbox / model 配置。改一处要改 5 处。

**建议**：
- 用 `application.properties`（公共）+ `application-{profile}.properties`（差异）
- 把 datasource 抽到 `application-datasource-mysql.properties` / `application-datasource-clickhouse.properties`，按需 include
- 或迁到 YAML，用 `spring.config.import` 组合

#### P2-5 SkillDistiller 用 regex 解析 LLM 输出

**问题**（v2 新增）：`SkillDistiller.java:66-99` 用 4 个 regex Pattern 解析 LLM 输出：
- `NAME_LINE` - 匹配 `Name: xxx`
- `DESC_LINE` - 匹配 `Description: xxx`
- `BODY_FENCE` - 匹配 ` ```markdown ... ``` ` 代码块
- `SAMPLES_BLOCK` - 匹配 `## Samples` 段落

注释 line 38 写："No JSON schema, no function-calling" - 即框架级的 `OutputParser` / `ResponseFormat` 未集成。

**风险**：
- LLM 输出格式稍变（如 `Name:xxx` 少一个空格、用 `###` 而非 `##`、加引号包裹）即解析失败
- SkillDistiller 失败 -> candidate rejected -> skill 蒸馏链路断
- [[distillation_agent_spawn_confusion]] 已记录 6.3 E2E 因 distill 失败卡住

**建议**：
- 改用框架 `OutputParser` + JSON schema（如果框架 RC5 支持）
- 或在 prompt 里加 ` Respond with JSON matching this schema: {...}`，用 Jackson 解析
- 保留 regex 作为 fallback，但加 metric 统计 regex 命中率，低于阈值告警
- 对应 `framework-highlights-verification.md` §4.1 缺口 2

#### P2-6 TraceMiner 绕过 MySqlEpisodicMemory API 直接 raw JDBC

**问题**（v2 新增）：`TraceMiner.java` 不调 `MySqlEpisodicMemory` 的检索 API，而是自己用 raw JDBC 扫 `agentscope_sessions` 表。注释写："so we can batch-scan large session ranges without loading every row through the reactive wrapper"。

**风险**：
- 封装泄漏 - `agentscope_sessions` 表结构是 `MySqlEpisodicMemory` 的内部细节，`TraceMiner` 直接依赖
- 表结构变更需要同时改两处（且 `TraceMiner` 改动不易发现）
- `SqlSessionFactory` 之外开了第三条 SQL 通道，事务/连接池治理更难
- `MySqlEpisodicMemory` 切换数据源时 `TraceMiner` 不会跟着切

**建议**：
- 在 `MySqlEpisodicMemory` 加 `streamSessionTraces(List<String> sessionIds, Consumer<Trace> consumer)` 批量流式 API
- `TraceMiner` 改成调这个 API，不再持有 raw JDBC
- 或抽出 `SessionTraceRepository` 作为两者共同的依赖，单一 SQL 通道

#### P2-7 硬编码常量散落各处

**问题**（v2 新增）：项目里大量调参常量硬编码在业务类里，无统一配置：

| 常量 | 位置 | 值 | 影响 |
|---|---|---|---|
| `TRACE_SNIPPETS` | `SkillDistiller.java:64` | 3 | 每次蒸馏用 3 条 trace，调大需要改代码 |
| episodic 查询 timeout | `SkillRetrievalHook.java:250` | 200ms | 慢查询会被截断返回空，无法调大 |
| `MAX_SKILL_BODY_INJECT` | `SkillRetrievalHook.java:70` | 2000 | skill 注入超过 2000 字截断 |
| `MAX_EPISODIC_SNIPPET_LEN` | `SkillRetrievalHook` | 300 | episodic snippet 截断长度 |
| `PREVIEW_CHARS` | `DebugController.java:70` | 1200 | debug 端点 preview 长度 |
| `PythonExecPropertiesV2.defaultTimeoutSeconds` | `V2ToolConfig.java:74` | 60 | python_exec 默认超时 |
| `PythonExecPropertiesV2.maxTimeoutSeconds` | `V2ToolConfig.java:75` | 300 | python_exec 最大超时 |
| cron `0 9 21 * * *` | `MemoryDigestionService` | 21:09 | 消化定时 |
| cron `0 17 * * * *` | `ArtifactSweeper` | 每小时 17 分 | 清扫定时 |

**建议**：
- 抽到 `@ConfigurationProperties` 类：`SkillProperties` / `EpisodicProperties` / `SandboxProperties`（部分已有）
- `application.properties` 加 `harness.skills.trace-snippets=3` / `harness.episodic.query-timeout-ms=200` 等
- 加 `@RefreshScope`（如果有 Spring Cloud Config）或至少文档化每个常量的调优含义

#### P2-8 ToolRoutersIndex 异常包装过度，丢失原始类型

**问题**（v2 新增）：`ToolRoutersIndex.java:89-114` 的 `router_tool` meta-tool 把所有异常统一包成 `IllegalArgumentException` 或 `IllegalStateException`：

```java
try {
    Method m = ...;
    return m.invoke(tool, args);
} catch (InvocationTargetException e) {
    throw new IllegalArgumentException("Tool " + toolId + " failed: " + e.getCause().getMessage(), e);
} catch (Exception e) {
    throw new IllegalStateException("Tool dispatch failed: " + e.getMessage(), e);
}
```

**风险**：
- 上层无法区分"参数错"（`IllegalArgumentException`）vs"工具内部失败"vs"下游服务挂"
- `PythonExecTool` 抛 `SandboxUnavailableException`（如果有的话）会被包成 `IllegalArgumentException`，FallbackModelDecorator 不知道该重试还是直接返回 4xx
- 错误日志里看到的全是 `IllegalArgumentException`，定位实际 root cause 需要翻 cause chain
- LLM 拿到的 error message 不含具体错误类型，无法决定下一步

**建议**：
- 定义 `ToolExecutionException extends RuntimeException` 基类，含 `toolId` / `cause-type` 字段
- `ToolRoutersIndex` 捕获时区分：
  - 参数反序列化失败 -> `ToolParameterException`
  - 工具方法找不到 -> `ToolNotFoundException`
  - 工具内部抛 `RuntimeException` -> 透传原异常（不要包装）
  - 工具内部抛 checked exception -> 包成 `ToolExecutionException`
- 上层根据异常类型决定重试策略

---

### P3 - 功能 / 性能

#### P3-2 skill 自进化 PR2/PR3/PR4 失败率统计需观察

**问题**：`harness.skills.evolution.enabled=true` + `fail-rate-evolve=0.3` + `fail-rate-blacklist=0.6` 已开启，但生产数据未观察。

**建议**：上线后 1-2 周查 `skill_index` 表的 `success_count` / `failure_count` 分布，确认阈值是否合理。

#### P3-3 ArithTool / python_exec 的 LLM 心算 fallback

**问题**：`[[arith_tool_e2e_verified]]` 记录 ArithTool 已 5/5 PASS，但 LLM 偶尔仍会绕过工具直接心算（AGENTS.md 已加强 prompt，但无强制约束）。

**建议**：在 `V2ToolGroupAdapter` 加 hook：检测 assistant 输出里含数字 + 算术符号但前一轮没调 arith，发 warn 日志。

#### P3-4 后台 cron 任务无失败告警

**问题**：`MemoryDigestionService` cron `0 9 21 * * *`、`ArtifactSweeper` cron `0 17 * * * *`，失败只 log，无告警。

**建议**：cron 失败时调 webhook（钉钉/飞书）或写 `agent_memory_ledger` 的 error 行。

---

## 三、推荐优化路径

按"先治理 -> 再优化 -> 后增强"顺序：

### 第一阶段（1-2 天）：治理 + 阻塞项
1. **P0-2**：分批 commit 175 个未提交改动
2. **P0-3**：删除 v1 代码树
3. **P0-1**：引入 Flyway，写 baseline 迁移
4. **P0-4**：引入 actuator + micrometer-prometheus
5. **P0-5**：实现 PythonExecAccessHook，堵 python_exec 跨租户漏洞

### 第二阶段（3-5 天）：架构重构
6. **P1-2**：修 5 个 RuntimeContextAware hook 的多用户串扰（系统性）
7. **P1-1**：拆 HarnessA2aRunnerV2 构造器
8. **P1-5**：修 V2ChatStreamServiceImpl 线程管理（executor 不 shutdown）
9. **P1-6**：清理 AgentscopeA2aApplication excludeFilters
10. **P1-7**：引入 @Transactional + DataSourceTransactionManager

### 第三阶段（5-10 天）：测试 + 异常体系
11. **P1-3**：补 v2 核心模块单元测试（优先 5 个 hook 的并发测试 + FallbackModelDecorator / V2ToolGroupAdapter / ToolRoutersIndex / DimensionStateManager）
12. **P1-4**：引入自定义异常体系，区分可恢复 vs 应抛出
13. **P2-8**：ToolRoutersIndex 异常分层（配合 P1-4）

### 第四阶段（持续）：代码质量
14. **P2-1**：拆超长类（TraceMiner / DimensionStateManager / SkillDistiller 优先）
15. **P2-5**：SkillDistiller 改用 JSON schema + OutputParser
16. **P2-6**：TraceMiner 改调 MySqlEpisodicMemory API
17. **P2-7**：硬编码常量抽到 @ConfigurationProperties
18. **P2-4**：配置文件重组

### 第五阶段（按需）：性能 + 增强
19. **P3-2**：观察 skill 自进化阈值
20. **P3-4**：cron 失败告警
21. **P3-3**：心算检测 hook

---

## 四、关键文件清单

| 文件 | 关联优化点 |
|---|---|
| `pom.xml` | P0-4 actuator |
| `src/main/java/com/agentscopea2a/AgentscopeA2aApplication.java` | P1-6 excludeFilters |
| `src/main/java/com/agentscopea2a/v2/runner/HarnessA2aRunnerV2.java` | P1-1 构造器爆炸 |
| `src/main/java/com/agentscopea2a/v2/service/V2ChatStreamServiceImpl.java` | P1-5 线程管理 / P1-4 异常吞 |
| `src/main/java/com/agentscopea2a/v2/dimension/DimensionStateManager.java` | P1-2 多用户串扰 / P2-1 超长类 |
| `src/main/java/com/agentscopea2a/v2/hooks/ArtifactHandoffHook.java` | P1-2 currentCtx 实例字段 |
| `src/main/java/com/agentscopea2a/v2/hooks/SkillEvolutionHook.java` | P1-2 currentCtx + currentTurnRetrieved |
| `src/main/java/com/agentscopea2a/v2/hooks/SkillRetrievalHook.java` | P1-2 currentCtx / P2-7 200ms timeout |
| `src/main/java/com/agentscopea2a/v2/hooks/SkillSynthesisHook.java` | P1-2 currentCtx |
| `src/main/java/com/agentscopea2a/v2/hooks/ToolCallTrackingHook.java` | P1-2 currentCtx |
| `src/main/java/com/agentscopea2a/v2/tools/PythonExecTool.java` | P0-5 跨桶防护空实现 |
| `src/main/java/com/agentscopea2a/v2/middleware/ArtifactAccessMiddleware.java` | P0-5 不拦 python_exec |
| `src/main/java/com/agentscopea2a/v2/tools/ToolRoutersIndex.java` | P2-8 异常包装过度 |
| `src/main/java/com/agentscopea2a/v2/skills/SkillDistiller.java` | P2-5 regex 解析 / P2-7 TRACE_SNIPPETS |
| `src/main/java/com/agentscopea2a/v2/digestion/TraceMiner.java` | P2-1 超长类 / P2-6 绕过 MySqlEpisodicMemory |
| `src/main/java/com/agentscopea2a/v2/memory/MySqlEpisodicMemory.java` | P1-7 无事务 / P1-4 12 处 catch / P2-1 超长类 |
| `src/main/java/com/agentscopea2a/v2/skills/SkillIndexRepository.java` | P1-7 无事务 / P0-1 DDL 硬编码 |
| `src/main/java/com/agentscopea2a/v2/config/V2InfraConfig.java` | P0-4 SimpleMeterRegistry / P0-5 装配 PythonExecAccessHook |
| `src/main/resources/db/migration/`（新建） | P0-1 Flyway baseline |

---

## 五、关联记忆

- [[dimension_state_persistence_gap]] - P1-2 DimensionStateManager 多用户串扰 bug（已扩展为系统性问题）
- [[async_tool_middleware_wiring]] - P0-4 监控缺失的相关上下文
- [[response_cache_deprecated]] - P3 ResponseCache HIT 路径已废弃
- [[sandbox_windows_boot_recipe]] - P2-4 配置文件依赖关系
- [[arith_tool_e2e_verified]] - P3-3 ArithTool 已 E2E 验证
- [[subagent_hook_chain_migration]] - P1-2 SubagentRegistrar 共享 hook bean 的上下文
- [[distillation_agent_spawn_confusion]] - P2-5 SkillDistiller 解析失败的 E2E 现场记录
- [[episodic_write_blocked]] - P1-7 MySqlEpisodicMemory 写入修复历史（事务缺失的相邻问题）
- [[ledger_mirror_middleware_design]] - P1-4 MemoryLedgerMirror 吞异常的相邻代码

---

## 六、不在本文档范围

- 框架本身（agentscope-java 2.0.0-RC5）的 bug
- 业务需求变更
- 前端 / UI 优化
- 网络 / 基础设施层面（SSH 通道 / Docker daemon / MySQL 远端）
- 密码明文外置（用户明确不考虑）
- JDK 17 -> 21 升级（用户明确不考虑）
- 多容器 / container pool（用户明确不考虑）

---

## 七、v2 修订日志

| 日期 | 修订内容 |
|---|---|
| 2026-07-18 v1 | 初版，P0-P3 优先级 + 推荐路径 |
| 2026-07-18 v2 | 3 轮深度代码分析后补充：P0-5 python_exec 跨租户漏洞、P1-2 系统化为 5 hook 串扰、P1-7 @Transactional 缺失、P2-5/P2-6/P2-7/P2-8 新增；移除密码明文 / JDK 升级 / 多容器 3 项 |
