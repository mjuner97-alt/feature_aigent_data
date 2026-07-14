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

所有变更通过 `mvn clean compile` 验证，v2 源文件从 56 增加到 63。

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

---

## 架构决策

| 决策 | 原因 | 影响 |
|------|------|------|
| ToolCallTrackingHook 改为无参 + ThreadLocal | V2ChatStreamServiceImpl 已通过 bind/unbind 管理 ToolCallCollector 生命周期，Hook 通过 ThreadLocal 获取，避免 per-request 创建 Hook 实例 | Hook 作为 singleton bean 在 HarnessA2aRunnerV2 中接线 |
| SessionMiddleware 实现 MiddlewareBase 接口 | v2 MiddlewareBase 是 interface（非 abstract class），必须用 `implements` 而非 `extends` | onActing 签名为 `(Agent, RuntimeContext, ActingInput, Function) → Flux` |
| MetricClassificationService 不再使用 @PostConstruct | v2 bean 由 V2SkillConfig 显式创建，需要手动调用 `init()` | V2SkillConfig 在创建 bean 后调用 `svc.init()` 加载 YAML 配置 |
| SkillCandidateRepository 移除 @PostConstruct | 同上，由 V2SkillConfig 显式调用 `initSchema()` | 表创建时机不变，仍然在应用启动时执行 |

---

## 编译验证

```
mvn clean compile → BUILD SUCCESS
```

63 个 v2 源文件编译通过。唯一警告：
- `ToolCallTrackingHook.java` 的 Hook/HookEvent/PreActingEvent/PostActingEvent 已弃用警告（已标记 `@SuppressWarnings("deprecation")`）
- Lombok `@EqualsAndHashCode(callSuper=false)` 遗留警告（与本次变更无关）

---

## v2 包新增文件

```
com.agentscopea2a.v2/
├── middleware/
│   └── SessionMiddleware.java              ← 新建（regex 清理 middleware，替代 v1 SessionHook）
├── skills/
│   ├── MetricClassificationService.java   ← git mv from harness/skills/
│   ├── FingerprintCalculator.java         ← git mv from harness/skills/
│   ├── SkillCandidate.java                ← git mv from harness/skills/
│   └── SkillCandidateRepository.java      ← git mv from harness/skills/
```

---

## 推迟到后续阶段的项

| 推迟项 | 阻塞依赖 | 解除阶段 |
|--------|----------|----------|
| V2ToolGroupAdapter（Toolkit + ToolGroup） | 需要验证 reset_equipped_tools meta-tool 行为 | 阶段 7 继续 |
| SkillSynthesisRunner / SkillEvolutionRunner 迁移 | 依赖 SkillDistiller | 阶段 8 |
| MemoryDigestionService 对接 | 依赖 SkillDistiller + FingerprintCalculator（已完成） | 阶段 8 |
| ToolCallCollector 持久化到情景记忆 | 需要验证 MySqlEpisodicMemory write API | 阶段 7 继续 |
| BotLoopGuard / IdempotencyStore | Channel-level，不适用于 streamEvents() runner | 阶段 8+ |
| V2SessionRouter 灰度路由 | 需要验证 v2 全链路后再添加 | 阶段 7 继续 |
| io.agentscope.* shadow override 清理 | src/main/java/io/ 下已无文件 | 阶段 7 继续 |
| io/ 目录根级 .class 清理 | 需要确认不影响 jar classpath | 阶段 9 |

---

## 阶段 7 验收缺口（需运行时验证）

- MetricClassificationService 加载 metric-categories.yaml 配置正确
- MetricClassificationService ruleBasedTag() 关键词匹配正确
- FingerprintCalculator computeMetric() 生成正确的语义指纹
- FingerprintCalculator compute() 生成正确的维度指纹（用于 ResponseCache）
- ToolCallTrackingHook 通过 ThreadLocal 正确追踪 L1 工具调用
- SessionMiddleware 清理 tool call input 中的 regex 模式
- SkillCandidateRepository 的 DDL 自动创建和 incrementHit/findByFingerprint 操作
- V2SkillConfig 正确创建所有 bean 并调用 init() 方法