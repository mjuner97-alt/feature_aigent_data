# 阶段 7 实施记录

> 实施日期：2026/07/14
> 升级分支：`upgrade/2.0.0-RC5-dual-track`

---

## 实施摘要

阶段 7 完成了以下工作：
- MetricClassificationService + SkillCandidate + SkillCandidateRepository 迁移到 v2/skills/
- FingerprintCalculator 迁移到 v2/skills/
- ToolCallTrackingHook 重构为无参构造函数 + ThreadLocal 模式，接入 HarnessA2aRunnerV2
- SessionMiddleware 创建（替代 v1 SessionHook），接入 HarnessA2aRunnerV2 middlewares 链
- V2SkillConfig 新增 MetricClassificationService、FingerprintCalculator、SkillCandidateRepository 三个 bean
- V2InfraConfig 新增 ToolCallTrackingHook、SessionMiddleware 两个 bean
- HarnessA2aRunnerV2 新增 ToolCallTrackingHook hook + SessionMiddleware middleware
- ChatRequest 移除对 v1 service 包的 import 依赖
- ToolCallCollector 持久化到情景记忆（V2ChatStreamServiceImpl 注入 EpisodicMemory，cleanup 中写入）
- EpisodicMemory 接口新增 `recordSessionWithToolContext()` 方法
- V2ToolGroupAdapter 创建（Toolkit + ToolGroup + MetaTool），替代 ToolRoutersIndex 的 flat router_tool 分发
- V2ToolConfig 新增 V2ToolGroupAdapter bean，HarnessA2aRunnerV2 接线 Toolkit
- V2SessionRouter 创建（灰度路由，默认 100% v2）
- BotLoopGuard / IdempotencyStore ADR 文档（确认为 Channel-level，不适用于 A2A runner）

所有变更通过 `mvn clean compile` 验证，v2 源文件从 56 增加到 67。

---

## 完成项

### 1. MetricClassificationService 迁移 ✅

- `git mv harness/skills/MetricClassificationService.java → v2/skills/MetricClassificationService.java`
- 包声明更新 `harness.skills → v2.skills`
- 移除 `@Service`、`@Value`、`@PostConstruct` 注解
- 构造函数改为显式参数：`(SkillCandidateRepository, Model, boolean, Path)`
- `ModelUtil.get(modelInstanceName)` → 构造函数注入 `Model lightModel`
- `init()` 方法改为公开方法（由 V2SkillConfig 调用），替代 `@PostConstruct`
- LLM 分类逻辑完全保留

### 2. SkillCandidate 迁移 ✅

- `git mv harness/skills/SkillCandidate.java → v2/skills/SkillCandidate.java`
- 包声明更新 `harness.skills → v2.skills`
- Javadoc 引用更新（移除对 v1 SkillSynthesisHook 的引用）

### 3. SkillCandidateRepository 迁移 ✅

- `git mv harness/skills/SkillCandidateRepository.java → v2/skills/SkillCandidateRepository.java`
- 包声明更新 `harness.skills → v2.skills`
- 移除 `@Repository`、`@Qualifier("mysqlDataSource")` 注解
- 构造函数改为 `(DataSource dataSource)`（无 @Qualifier）
- `@PostConstruct initSchema()` → `initSchema()` 公开方法（由 V2SkillConfig 调用）
- Javadoc 引用更新

### 4. FingerprintCalculator 迁移 ✅

- `git mv harness/skills/FingerprintCalculator.java → v2/skills/FingerprintCalculator.java`
- 包声明更新 `harness.skills → v2.skills`
- 移除 `@Component` 注解
- import 更新：`DimensionStateManager → v2.dimension.DimensionStateManager`、`MetricClassificationService → v2.skills.MetricClassificationService`、`ResponseCacheService → v2.cache.ResponseCacheService`、`QuestionAnalysis → v2.dimension.QuestionAnalysis`
- 构造函数保持不变（已是无参 Spring 注入）
- Javadoc 更新（添加 bean 创建说明）
- `@Deprecated` 方法保留（`compute()`、`format()`、`fingerprint()` 用于 ResponseCache 兼容）

### 5. ToolCallTrackingHook 重构 + 接线 ✅

- 重构为**无参构造函数 + ThreadLocal 模式**：移除 `(ToolCallCollector collector)` 构造参数
- `onEvent()` 方法改为通过 `ToolCallCollector.getCurrent()` 获取 per-request collector
- 当 collector 为 null（非请求上下文）时静默跳过
- V2InfraConfig 新增 `ToolCallTrackingHook` bean（无参构造）
- HarnessA2aRunnerV2 新增 `ObjectProvider<ToolCallTrackingHook>` + `.hook(trackingHook)` 条件化接线

### 6. SessionMiddleware 创建 ✅

- 新建 `v2/middleware/SessionMiddleware.java`
- 实现 `MiddlewareBase.onActing()` 接口（替代 v1 `SessionHook` 的 Hook API）
- 功能：检测 tool call input 中包含 "regex" 模式的参数值，替换为空格
- 使用正则 `Pattern.compile("\\bregex\\b", CASE_INSENSITIVE)` 匹配
- V2InfraConfig 新增 `SessionMiddleware` bean
- HarnessA2aRunnerV2 新增 `sessionMiddleware` 到 middlewares 链（第 5 个 middleware）

### 7. V2SkillConfig 扩展 ✅

- 新增 `SkillCandidateRepository(DataSource)` bean — 调用 `initSchema()` 创建表
- 新增 `MetricClassificationService` bean — 注入 `SkillCandidateRepository`、`Model`（light-classifier）、配置参数
- 新增 `FingerprintCalculator` bean — 注入 `DimensionStateManager`、`MetricClassificationService`

### 8. ChatRequest 修复 ✅

- 移除对 `com.agentscopea2a.service.ChatStreamServiceV_3` 的 import（v1 service 包被 Maven 排除）
- Javadoc 引用更新

### 9. ToolCallCollector 持久化到情景记忆 ✅

- `V2ChatStreamServiceImpl` 注入 `EpisodicMemory`，在 cleanup 中将 tool call context 写入情景记忆
- `collector.bind()` 从 HTTP 线程移到 executor 线程（修复 ThreadLocal 跨线程问题）
- 在 `collector.unbind()` 之前，调用 `episodicMemory.recordSessionWithToolContext(sessionId, messages, toolCallJson)` 写入
- `EpisodicMemory` 接口新增 `recordSessionWithToolContext()` 方法签名
- 持久化异步执行（`subscribeOn(Schedulers.boundedElastic())`），不阻塞 SSE 流

### 10. V2ToolGroupAdapter 创建 ✅

- 新建 `v2/tools/V2ToolGroupAdapter.java` — 基于 v2 Toolkit API 的工具组适配器
- 使用 Builder 模式：`V2ToolGroupAdapter.builder().createGroup().tool().enableMetaTool().build()`
- 创建 `quality_tools`（META scope, active）和 `data_primitives`（META scope, active）两个工具组
- 注册 `reset_equipped_tools` meta-tool（LLM 可动态切换工具组）
- V2ToolConfig 新增 `V2ToolGroupAdapter` bean
- HarnessA2aRunnerV2 新增 `ObjectProvider<V2ToolGroupAdapter>` + `.toolkit(adapter.getToolkit())` 条件化接线
- ToolRoutersIndex bean 保留作为 fallback

### 11. V2SessionRouter 创建 ✅

- 新建 `v2/routing/V2SessionRouter.java` — 灰度路由组件
- 基于 `harness.routing.v2-percentage` 配置（默认 100% = 全 v2）
- Session stickiness：同一 conversationId 始终路由到相同入口
- 使用 `ConcurrentHashMap` 存储路由决策
- 当前默认 100% v2，当 v1 controller 重新启用后可用于灰度切换

### 12. BotLoopGuard / IdempotencyStore ADR ✅

- ADR-7.2 文档确认 BotLoopGuard 和 IdempotencyStore 为 Channel-level 组件
- 不适用于 `streamEvents()` runner（无 webhook 回调、无 bot-to-bot 循环风险）
- `HarnessAgent.Builder` 不暴露 `.botLoopGuard()` 或 `.idempotencyStore()` 方法
- 推迟到 Stage 8+（Channel/Gateway 集成时再考虑）

---

## 架构决策

| 决策 | 原因 | 影响 |
|------|------|------|
| ToolCallTrackingHook 改为无参 + ThreadLocal | V2ChatStreamServiceImpl 已通过 bind/unbind 管理 ToolCallCollector 生命周期，Hook 通过 ThreadLocal 获取，避免 per-request 创建 Hook 实例 | Hook 作为 singleton bean 在 HarnessA2aRunnerV2 中接线 |
| SessionMiddleware 实现 MiddlewareBase 接口 | v2 MiddlewareBase 是 interface（非 abstract class），必须用 `implements` 而非 `extends` | onActing 签名为 `(Agent, RuntimeContext, ActingInput, Function) → Flux` |
| MetricClassificationService 不再使用 @PostConstruct | v2 bean 由 V2SkillConfig 显式创建，需要手动调用 `init()` | V2SkillConfig 在创建 bean 后调用 `svc.init()` 加载 YAML 配置 |
| SkillCandidateRepository 移除 @PostConstruct | 同上，由 V2SkillConfig 显式调用 `initSchema()` | 表创建时机不变，仍然在应用启动时执行 |
| ToolCallCollector.bind() 移到 executor 线程 | 原来 bind() 在 HTTP 线程执行，但 streamEvents() 在 executor 线程执行，ThreadLocal 不跨线程 | bind() 移到 Executors.newSingleThreadExecutor().submit() 内部，确保 hook 可访问 |
| V2ToolGroupAdapter 使用 Builder 模式 | Toolkit 注册 API 较复杂，Builder 提供流式 API | V2ToolConfig 通过 V2ToolGroupAdapter.builder() 配置工具组 |
| V2SessionRouter 默认 100% v2 | 当前只有 v2 入口启用，v1 路由在 Stage 8+ 灰度切换时启用 | 不影响当前行为，预留灰度能力 |
| BotLoopGuard / IdempotencyStore 不迁移 | Channel-level 组件，不适用于 streamEvents() runner | 未来如需 webhook 集成，创建 Channel adapter |

---

## 编译验证

```
mvn clean compile → BUILD SUCCESS
```

67 个 v2 源文件编译通过。唯一警告：
- `ToolCallTrackingHook.java` 的 Hook/HookEvent/PreActingEvent/PostActingEvent 已弃用警告（已标记 `@SuppressWarnings("deprecation")`）
- Lombok `@EqualsAndHashCode(callSuper=false)` 遗留警告（与本次变更无关）

---

## v2 包新增文件

```
com.agentscopea2a.v2/
├── middleware/
│   └── SessionMiddleware.java              ← 新建（regex 清理 middleware，替代 v1 SessionHook）
├── routing/
│   └── V2SessionRouter.java                ← 新建（灰度路由，默认 100% v2）
├── skills/
│   ├── MetricClassificationService.java   ← git mv from harness/skills/
│   ├── FingerprintCalculator.java         ← git mv from harness/skills/
│   ├── SkillCandidate.java                ← git mv from harness/skills/
│   └── SkillCandidateRepository.java      ← git mv from harness/skills/
├── tools/
│   └── V2ToolGroupAdapter.java            ← 新建（Toolkit + ToolGroup + MetaTool 适配器）
```

## 修改的现有 v2 文件

```
com.agentscopea2a.v2/
├── config/
│   ├── V2InfraConfig.java                  ← 新增 ToolCallTrackingHook + SessionMiddleware bean
│   ├── V2SkillConfig.java                  ← 新增 SkillCandidateRepository + MetricClassificationService + FingerprintCalculator bean
│   └── V2ToolConfig.java                  ← 新增 V2ToolGroupAdapter bean
├── hooks/
│   └── ToolCallTrackingHook.java          ← 重构为无参构造函数 + ThreadLocal
├── memory/
│   ├── EpisodicMemory.java                ← 接口新增 recordSessionWithToolContext() 方法
│   └── MySqlEpisodicMemory.java           ← 已有 recordSessionWithToolContext() 实现（新增到接口）
├── runner/
│   └── HarnessA2aRunnerV2.java            ← 新增 V2ToolGroupAdapter + Toolkit 接线
├── service/
│   └── V2ChatStreamServiceImpl.java       ← 注入 EpisodicMemory，bind() 移到 executor 线程，持久化 tool call context
└── dto/
    └── ChatRequest.java                   ← 移除 v1 service 包 import
```

---

## 推迟到后续阶段的项

| 推迟项 | 阻塞依赖 | 解除阶段 |
|--------|----------|----------|
| SkillSynthesisRunner / SkillEvolutionRunner 迁移 | 依赖 SkillDistiller | 阶段 8 |
| MemoryDigestionService 对接 | 依赖 SkillDistiller + FingerprintCalculator（已完成） | 阶段 8 |
| ToolRoutersIndex 移除（当 Toolkit 路径验证通过后） | 需要验证 reset_equipped_tools meta-tool 运行时行为 | 阶段 8 |
| V2SessionRouter 灰度激活 | 需要验证 v2 全链路 + 重新启用 v1 controller | 阶段 8 |
| io/ 目录根级 .class 清理 | 需要确认不影响 jar classpath | 阶段 9 |
| v1 文件清理（全部 43 个文件） | 等待 v2 全链路验证通过 | 阶段 9 |

---

## 阶段 7 验收缺口（需运行时验证）

- MetricClassificationService 加载 metric-categories.yaml 配置正确
- MetricClassificationService ruleBasedTag() 关键词匹配正确
- FingerprintCalculator computeMetric() 生成正确的语义指纹
- FingerprintCalculator compute() 生成正确的维度指纹（用于 ResponseCache）
- ToolCallTrackingHook 通过 ThreadLocal 正确追踪 L1 工具调用（bind 在 executor 线程）
- SessionMiddleware 清理 tool call input 中的 regex 模式
- SkillCandidateRepository 的 DDL 自动创建和 incrementHit/findByFingerprint 操作
- V2SkillConfig 正确创建所有 bean 并调用 init() 方法
- ToolCallCollector 持久化到情景记忆正确工作（路径 B/C）
- V2ToolGroupAdapter 正确创建工具组并注册 meta-tool
- reset_equipped_tools meta-tool 允许 LLM 动态切换工具组
- V2SessionRouter 默认路由 100% 到 v2