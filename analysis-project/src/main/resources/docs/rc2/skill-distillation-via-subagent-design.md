# 路径 A 自动蒸馏改造方案：用 generate_skill 子智能体替代 SkillDistiller 直调

## 1. 问题背景

当前路径 A（三次命中自动蒸馏）通过 SkillDistiller 直接调用 LLM 生成 SKILL.md，存在两个问题：

1. **思考标签污染**：内网模型（qwen3:8b 等）在回答前输出 thinking 标签，`appendChunk()` 原样拼接导致 SKILL.md body 包含思考过程（已在 `stripThinkingTags()` 中修复，但治标不治本）
2. **生成质量差**：SkillDistiller 的 prompt 只有"用户问题+fingerprint+metricTag+工具调用链路摘要"，缺少完整对话上下文；而路径 B 的 generate_skill 子智能体能看到完整对话，生成的 skill 质量更高

改造后：路径 A 也走 generate_skill 子智能体，蒸馏结果通过捕获 `save_skill` 工具调用参数获得（不再正则解析 LLM 输出），消除 thinking 标签污染问题并提升蒸馏质量。

## 2. 两条路径对比

### 路径 A（当前）：SkillDistiller 直调 LLM

```
三次命中 → SkillSynthesisRunner.distillAndSave()
                ↓
        SkillDistiller.callModel() / callModelWithContext()
                ↓                              ↓
        精简 prompt 直接调 LLM          LLM 返回四段结构化文本
        (question+fp+metricTag+toolCtx)  (name/desc/samples/body)
                ↓
        parseLenient() 正则解析
                ↓
        DistilledSkill → saveSkillWithMetricTag() → skills-auto/<name>/SKILL.md
```

**问题**：LLM 输出可能包含 thinking 标签，且精简 prompt 缺少完整上下文。

### 路径 B（用户主动保存）：generate_skill 子智能体

```
用户请求"保存为skill" → Supervisor 调用 agent_spawn("generate_skill")
                              ↓
                    generate_skill 子智能体（maxIters=3）
                    system prompt = generate_skill.md + ToolCallCollector 工具调用链路
                              ↓
                    子智能体 LLM 分析对话 + 工具链路
                              ↓
                    调用 save_skill(name, desc, content)
                              ↓
                    SkillSaveTool.saveSkill() → skills-auto/<name>/SKILL.md
```

**优势**：LLM 能看到完整对话上下文，thinking 标签由框架自动处理。

## 3. 改造方案：路径 A 也走 generate_skill 子智能体

### 核心思路

将 SkillDistiller 的"直接调 LLM + 正则解析"模式，替换为"创建临时 HarnessAgent 实例运行 generate_skill 子智能体"。蒸馏结果通过捕获 `save_skill` 工具调用参数获得，而不是正则解析 LLM 输出。

### 异步执行保证

改造后蒸馏仍然是**异步执行**，不影响用户实时流程。`maybeDispatch()` 通过 `Mono.fromRunnable(...).subscribeOn(Schedulers.boundedElastic())` 将整个 `distillAndSave()` 调度到 boundedElastic 线程，与原方案一样。子智能体通过 `agent.call(msgs, ctx).block()` 同步运行在该线程上——与原方案中 `model.stream().reduce().block()` 的阻塞模型完全等价，只是 LLM 交互从"单次流式调用"变为"多轮 agent 循环"。

### 改造后流程

```
SkillSynthesisRunner.distillAndSave() (异步线程 boundedElastic)
    |
    v
[1] 获取工具调用上下文（同现有逻辑）
    toolCallContext = mem.searchToolContextByFingerprint(fingerprint)
    |
    v
[2] 构建蒸馏子智能体
    HarnessAgent agent = distiller.buildDistillAgent(question, toolCallContext, metricTag, captureTool)
    - system prompt = generate_skill.md 内容 + toolCallContext + metricContext
    - toolkit = { save_skill: CaptureSkillSaveTool }
    - maxIters = 3
    |
    v
[3] 构建用户消息 + 运行子智能体 (同步, boundedElastic 线程)
    Msg userMsg = buildDistillTask(question, fingerprint, toolCallContext)
    agent.call(List.of(userMsg), runtimeContext).block()
    |
    v
[4] 捕获 save_skill 调用参数
    CaptureSkillSaveTool.CapturedSkill captured = captureTool.getCaptured()
    |
    v
[5] 构造 DistilledSkill → metricTag 印章 → CAS → 写磁盘 → embedding (同现有逻辑)
```

## 4. 关键组件设计

### 4.1 CaptureSkillSaveTool（新增）

**文件**: `src/main/java/com/agentscopea2a/harness/tools/CaptureSkillSaveTool.java`

替代真实 SkillSaveTool 的捕获版本——不写磁盘，只记录参数，供 SkillSynthesisRunner 后续处理：

```java
package com.agentscopea2a.harness.tools;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;

/**
 * 捕获版 SkillSaveTool —— 蒸馏子智能体使用。
 * 不写磁盘，只记录 save_skill 的参数，供 SkillSynthesisRunner 后续处理。
 */
public class CaptureSkillSaveTool {

    private volatile CapturedSkill captured;

    public record CapturedSkill(String name, String description, String content) {}

    @Tool(name = "save_skill",
          description = "将生成的技能内容保存为SKILL.md文件。"
                      + "skill_name使用英文小写+下划线命名，"
                      + "content是SKILL.md的正文部分（不含YAML frontmatter，"
                      + "系统会自动生成 name/description/version/last_evolved_at）。")
    public ToolResultBlock saveSkill(
            @ToolParam(name = "skill_name",
                       description = "技能名称，使用英文小写+下划线（如 quality_query_analysis）")
            String skillName,
            @ToolParam(name = "description",
                       description = "技能的一句话中文描述")
            String description,
            @ToolParam(name = "content",
                       description = "SKILL.md的完整正文内容（不含YAML frontmatter）")
            String content) {
        this.captured = new CapturedSkill(skillName, description, content);
        return ToolResultBlock.text("Skill '" + skillName + "' saved successfully.");
    }

    public CapturedSkill getCaptured() { return captured; }
    public boolean hasCaptured() { return captured != null; }
}
```

关键设计：
- `@Tool` 注解与 SkillSaveTool 完全一致（name="save_skill"，参数名/描述相同），确保 LLM 看到相同的工具描述
- `volatile` 保证子智能体线程写入后 SkillSynthesisRunner 线程可见
- 不涉及磁盘写入 / embedding / index 操作——这些由 SkillSynthesisRunner 后续统一处理
- 返回成功消息而非错误，让子智能体自然结束

### 4.2 SkillDistiller 新增 buildDistillAgent 方法

**文件**: `src/main/java/com/agentscopea2a/harness/skills/SkillDistiller.java`

在 SkillDistiller 中新增方法，构建临时 HarnessAgent 实例：

```java
// 新增依赖注入
private final Path workspace;

// 构造函数增加 workspace 参数
public SkillDistiller(Model model, ObjectProvider<EpisodicMemory> episodicProvider,
                      MetricClassificationService metricClassifier, Path workspace) {
    this.model = model;
    this.episodicProvider = episodicProvider;
    this.metricClassifier = metricClassifier;
    this.workspace = workspace;
}

/**
 * 构建用于蒸馏的临时 HarnessAgent 实例。
 * system prompt = generate_skill.md 内容 + 工具调用上下文 + metricTag 上下文
 * toolkit = { save_skill: CaptureSkillSaveTool }
 * maxIters = 3
 */
public HarnessAgent buildDistillAgent(
        String question, String toolCallContext, String metricTag,
        CaptureSkillSaveTool captureTool) {

    // 1. 加载 generate_skill.md 作为 system prompt 基础
    Path specPath = workspace.resolve("agent-subagents").resolve("generate_skill.md");
    String sysPrompt;
    try {
        sysPrompt = Files.readString(specPath);
    } catch (Exception e) {
        log.warn("Failed to load generate_skill.md, using fallback prompt");
        sysPrompt = "你是技能生成助手。请根据用户问题和工具调用链路，蒸馏为可复用的 SKILL.md，并调用 save_skill 保存。";
    }

    // 2. 注入工具调用上下文（与路径 B 相同的方式）
    if (toolCallContext != null && !toolCallContext.isBlank()) {
        sysPrompt += "\n\n" + toolCallContext;
    }

    // 3. 注入 metricTag 上下文
    String metricContext = metricHint(metricTag);
    if (!metricContext.isEmpty()) {
        sysPrompt += "\n" + metricContext;
    }

    // 4. 构建工具注册表（只有 save_skill）
    Toolkit tk = new Toolkit();
    tk.registerTool(captureTool);

    // 5. 构建子智能体
    return HarnessAgent.builder()
            .name("skill_distiller")
            .model(model)
            .workspace(workspace)
            .toolkit(tk)
            .sysPrompt(sysPrompt)
            .maxIters(3)
            .enablePendingToolRecovery(true)
            .build();
}
```

关键设计：
- 从 `workspace/agent-subagents/generate_skill.md` 加载 system prompt（与路径 B 完全一致）
- `toolCallContext` 已由 `SkillSynthesisRunner.buildEnrichedContext()` 格式化为 LLM 友好的编号步骤列表
- `metricHint()` 方法已有，输出格式如 `\n**指标分类**: defect_density (缺陷密度)\n请围绕[缺陷密度]这一指标类别来编写 skill...`
- 复用 SkillDistiller 现有的 `model` 实例（即 `harness.a2a.model.default-model`）
- `enablePendingToolRecovery(true)` 防止 save_skill 调用偶尔被跳过

### 4.3 SkillSynthesisRunner 改造

**文件**: `src/main/java/com/agentscopea2a/harness/skills/SkillSynthesisRunner.java`

改造 `distillAndSave()` 方法，根据 `viaSubagent` 开关选择路径：

```java
// 新增字段
private final boolean viaSubagent;

// 构造函数增加参数
@Value("${harness.skills.auto-synth.via-subagent:true}") boolean viaSubagent

private void distillAndSave(
        String fingerprint, String userId, String question, SkillCandidate candidate) {

    log.info("Skill synthesis triggered: fingerprint={} hit={} user={}",
            fingerprint, candidate.hitCount(), userId);

    // [1] 获取工具调用上下文（同现有逻辑）
    String toolCallContext = "";
    if (episodicMemory instanceof MySqlEpisodicMemory mem) {
        try {
            toolCallContext = mem.searchToolContextByFingerprint(fingerprint).block();
            if (toolCallContext != null && !toolCallContext.isBlank()) {
                log.info("Found tool call context for fingerprint={} ({} chars)",
                        fingerprint, toolCallContext.length());
            }
        } catch (Exception e) {
            log.debug("Failed to fetch tool context for {}: {}", fingerprint, e.getMessage());
        }
    }

    String metricTag = candidate.metricTag();

    if (viaSubagent) {
        distillViaSubagent(fingerprint, userId, question, candidate, toolCallContext, metricTag);
    } else {
        distillViaDirectLlm(fingerprint, userId, question, candidate, toolCallContext, metricTag);
    }
}

/**
 * 新路径：走 generate_skill 子智能体蒸馏
 */
private void distillViaSubagent(
        String fingerprint, String userId, String question,
        SkillCandidate candidate, String toolCallContext, String metricTag) {

    // [2] 构建蒸馏子智能体
    CaptureSkillSaveTool captureTool = new CaptureSkillSaveTool();
    HarnessAgent distillAgent;
    try {
        String enrichedContext = buildEnrichedContext(question, toolCallContext);
        distillAgent = distiller.buildDistillAgent(question, enrichedContext, metricTag, captureTool);
    } catch (Exception e) {
        log.warn("Failed to build distill agent: {}", e.getMessage());
        candidateRepo.markRejected(fingerprint, "subagent_build_failed");
        return;
    }

    // [3] 构建用户消息（metricContext 已在 system prompt 中注入，这里不重复）
    String task = buildDistillTask(question, fingerprint, toolCallContext);
    Msg userMsg = Msg.builder()
            .role(MsgRole.USER)
            .content(TextBlock.builder().text(task).build())
            .build();

    // [4] 运行子智能体（同步，已在 boundedElastic 线程上）
    RuntimeContext ctx = RuntimeContext.builder()
            .sessionId("distill-" + fingerprint)
            .userId(userId)
            .sessionKey(SimpleSessionKey.of("distill-" + fingerprint))
            .build();
    try {
        distillAgent.call(List.of(userMsg), ctx).block();
    } catch (Exception e) {
        log.warn("Distill subagent failed: {}", e.getMessage());
        candidateRepo.markRejected(fingerprint, "subagent_failed");
        return;
    }

    // [5] 捕获结果
    if (!captureTool.hasCaptured()) {
        log.warn("Distill subagent did not call save_skill");
        candidateRepo.markRejected(fingerprint, "subagent_no_save_skill");
        return;
    }

    CaptureSkillSaveTool.CapturedSkill captured = captureTool.getCaptured();
    String name = sanitizeName(captured.name());
    String description = captured.description();
    String body = SkillSaveTool.stripFrontmatter(captured.content());
    List<String> samples = SkillDistiller.parseSamples(body);

    DistilledSkill distilled = new DistilledSkill(name, description, body, samples);

    // [6] metricTag 印章
    if (metricTag != null && !metricTag.isBlank()) {
        distilled = withMetricTag(distilled, metricTag);
        log.info("Distilled skill '{}' tagged with metric_tag={}", distilled.name(), metricTag);
    }

    // [7] CAS + 写磁盘 + embedding（同现有逻辑）
    saveDistilledSkill(distilled, fingerprint, metricTag);
}

/**
 * 回退路径：原有 SkillDistiller 直调 LLM（保留作为 fallback）
 */
private void distillViaDirectLlm(
        String fingerprint, String userId, String question,
        SkillCandidate candidate, String toolCallContext, String metricTag) {

    DistilledSkill distilled;
    if (toolCallContext != null && !toolCallContext.isBlank()) {
        String enrichedContext = buildEnrichedContext(question, toolCallContext);
        distilled = distiller.distillWithContext(question, fingerprint, enrichedContext, metricTag).block();
    } else {
        distilled = distiller.distill(question, fingerprint, metricTag).block();
    }
    if (distilled == null) {
        candidateRepo.markRejected(fingerprint, "distiller_returned_null");
        return;
    }
    if (metricTag != null && !metricTag.isBlank()) {
        distilled = withMetricTag(distilled, metricTag);
        log.info("Distilled skill '{}' tagged with metric_tag={}", distilled.name(), metricTag);
    }
    saveDistilledSkill(distilled, fingerprint, metricTag);
}

/**
 * 公共保存逻辑：CAS + 写磁盘 + embedding
 */
private void saveDistilledSkill(DistilledSkill distilled, String fingerprint, String metricTag) {
    if (!candidateRepo.markSynthesized(fingerprint, distilled.name())) {
        log.info("Candidate {} already claimed; skipping save", fingerprint);
        return;
    }
    try {
        SkillSaveTool saver = new SkillSaveTool(skillsDir, indexRepo, vectorIndex, null);
        boolean saved = saver.saveSkillWithMetricTag(
                distilled.name(), distilled.description(), distilled.body(), metricTag);
        if (!saved) {
            log.warn("SkillSaveTool returned false for '{}'", distilled.name());
        }
        if (vectorIndex != null && embeddingClient != null) {
            String embedText = buildEmbedText(distilled);
            float[] vec = embeddingClient.embed(embedText);
            if (vec != null) vectorIndex.upsertVector(distilled.name(), fingerprint, vec);
        }
        log.info("Auto-synthesised skill '{}' from fingerprint {}", distilled.name(), fingerprint);
    } catch (Exception ex) {
        log.warn("SkillSaveTool failed for '{}': {}", distilled.name(), ex.getMessage());
    }
}

/**
 * 蒸馏任务的 user message
 */
private String buildDistillTask(String question, String fingerprint, String toolCallContext) {
    StringBuilder sb = new StringBuilder();
    sb.append("请将以下用户问题的处理流程蒸馏为一个可复用的 skill：\n\n");
    sb.append("**用户问题**: ").append(question).append("\n");
    sb.append("**Fingerprint**: ").append(fingerprint).append("\n");
    if (toolCallContext != null && !toolCallContext.isBlank()) {
        sb.append(toolCallContext).append("\n");
    }
    sb.append("\n请按以下步骤操作：\n");
    sb.append("1. 分析用户问题和工具调用链路，提取核心工作流程\n");
    sb.append("2. 用英文小写+下划线给技能命名（如 quality_query_analysis）\n");
    sb.append("3. 写一句话中文描述\n");
    sb.append("4. 把工作流程整理为 SKILL.md 正文（不要 YAML frontmatter，系统会自动加）\n");
    sb.append("5. 调用 save_skill 工具保存\n");
    sb.append("\n注意：工具名只能用真实名称（tool_index / toolMetaInfo / router_tool），不要使用泛化名称。");
    return sb.toString();
}

private String sanitizeName(String name) {
    if (name == null || name.isBlank()) return "skill_" + Integer.toHexString(name.hashCode()).substring(0, 8);
    return name.trim().toLowerCase().replaceAll("[^a-z0-9_]", "_");
}
```

### 4.4 SkillSynthesisRunner 构造函数改造

```java
public SkillSynthesisRunner(
        SkillCandidateRepository candidateRepo,
        SkillIndexRepository indexRepo,
        SkillDistiller distiller,
        ObjectProvider<SkillVectorIndex> vectorIndexProvider,
        ObjectProvider<EmbeddingClient> embeddingClientProvider,
        ObjectProvider<EpisodicMemory> episodicMemoryProvider,
        @Value("${harness.a2a.workspace.path}") String workspaceRoot,
        @Value("${harness.skills.auto-synth.enabled:false}") boolean enabled,
        @Value("${harness.skills.auto-synth.threshold:3}") int hitThreshold,
        @Value("${harness.skills.auto-synth.via-subagent:true}") boolean viaSubagent) {
    // ... 现有字段初始化 ...
    this.viaSubagent = viaSubagent;
}
```

## 5. 保留 SkillDistiller 的 evolve 路径

**重要**：`SkillDistiller.evolve()` 方法（PR4 失败反馈闭环）仍然需要保留，因为它是修订已有 skill，不需要走子智能体。只改造 `distill()` 和 `distillWithContext()` 路径。

`stripThinkingTags()` 也保留作为 evolve 路径的安全网。

需要新增的可见性调整：
- `SkillDistiller.parseSamples()` 改为 `public static`（供 SkillSynthesisRunner 调用，目前是 `static` 包级私有）
- `SkillDistiller.metricHint()` 改为 `public`（供 `buildDistillAgent()` 在 SkillDistiller 内部调用，无可见性问题——已在同一个类中）
- `SkillDistiller` 构造函数增加 `Path workspace` 参数（Spring 注入需要调整）
- 新增 `SkillDistiller.getWorkspace()` getter 或将 `workspace` 传递到 `buildDistillAgent()` 中使用

## 6. 回退机制

新增配置开关 `harness.skills.auto-synth.via-subagent`：

```properties
# 蒸馏路径选择：true=走 generate_skill 子智能体，false=走原有 SkillDistiller 直调 LLM
harness.skills.auto-synth.via-subagent=true
```

- `true`（默认）：走子智能体蒸馏路径
- `false`：回退到原有 SkillDistiller 直调 LLM 路径

SkillSynthesisRunner 根据 `viaSubagent` 标志选择路径：

```java
if (viaSubagent) {
    // 新路径：走 generate_skill 子智能体
    distillViaSubagent(fingerprint, userId, question, candidate, toolCallContext, metricTag);
} else {
    // 回退路径：原有 SkillDistiller 直调 LLM
    distillViaDirectLlm(fingerprint, userId, question, candidate, toolCallContext, metricTag);
}
```

## 7. 改动文件清单

| # | 文件 | 改动 |
|---|------|------|
| 1 | **新增** `CaptureSkillSaveTool.java` | 捕获版 SkillSaveTool，不写磁盘只记录参数 |
| 2 | **修改** `SkillDistiller.java` | 新增 `buildDistillAgent()` 方法；构造函数增加 `Path workspace`；`parseSamples()` 改为 `public static`；`buildDistillAgent()` 加 `.disableMemoryHooks()` |
| 3 | **修改** `SkillSynthesisRunner.java` | `distillAndSave()` 根据 via-subagent 开关分两条路径；新增 `distillViaSubagent()`、`distillViaDirectLlm()`、`saveDistilledSkill()`、`buildDistillTask()`、`sanitizeName()` 方法；构造函数增加 `viaSubagent` 参数；`distillViaSubagent()` 对 userId 和 fingerprint 做非法路径字符清理；`distillViaSubagent()` 循环剥离 frontmatter；`buildDistillTask()` 增加详细正文结构要求 |
| 4 | **修改** `application.properties` | 新增 `harness.skills.auto-synth.via-subagent=true` 开关 |
| 5 | **修改** `generate_skill.md` | 正文结构模板从"建议"改为"必须"，增加详细章节要求 |

**不需要修改**：
- `SupervisorService.java` —— SkillDistiller 自身持有 `model` 和 `workspace`，无需通过 SupervisorService 传递
- `SkillSaveTool.java` —— `stripFrontmatter()` 已是 `static`，可直接调用

## 8. 已知问题与修复

### 8.1 Windows 路径非法字符问题（已修复）

fingerprint 中包含 `|` 字符（如 `_global|query|general`），在 Windows 文件系统中是非法路径字符。
当 HarnessAgent 的 `SubagentsHook` 尝试创建任务文件时，路径中包含 `|` 导致 `InvalidPathException`：

```
InvalidPathException: Illegal char <|> at index 60: u:u-analysisliw/agents/skill_distiller/tasks/distill-_global|query|general.json
```

**修复**：在 `distillViaSubagent()` 中构建 `RuntimeContext` 时，对 fingerprint 中的非法路径字符进行替换：

```java
String safeFingerprint = fingerprint.replaceAll("[|<>:\"?*\\\\/]", "_");
// userId 也需清理（见 §8.2）
RuntimeContext ctx = RuntimeContext.builder()
        .sessionId("distill-" + safeFingerprint)
        .userId(userId.replaceAll("[|<>:\"?*\\\\/]", "_"))
        .sessionKey(SimpleSessionKey.of("distill-" + safeFingerprint))
        .build();
```

### 8.2 userId 包含 `:` 导致 MemoryFlushHook 崩溃（已修复）

`userId` 值如 `u:u-analysisliw` 包含 `:`，Windows 将其解释为驱动器号分隔符。
`MemoryFlushHook` → `SessionTree.mirrorToFilesystem` → `LocalFilesystem.uploadFiles` → `Files.createDirectories`
尝试创建路径 `u:u-analysisliw/agents/skill_distiller/...`，抛出：

```
IOError: java.io.IOException: Unable to get working directory of drive 'U'
```

这导致 `distillAgent.call().block()` 抛异常，CaptureSkillSaveTool 的结果被吞掉，skill 被 `markRejected(fingerprint, "subagent_failed")`。

**修复**（双保险）：

1. 在 `distillViaSubagent()` 中对 userId 做与 fingerprint 相同的清理：

```java
String safeUserId = userId.replaceAll("[|<>:\"?*\\\\/]", "_");
RuntimeContext ctx = RuntimeContext.builder()
        .sessionId("distill-" + safeFingerprint)
        .userId(safeUserId)
        .sessionKey(SimpleSessionKey.of("distill-" + safeFingerprint))
        .build();
```

2. 在 `SkillDistiller.buildDistillAgent()` 中加 `.disableMemoryHooks()`：
蒸馏子智能体是临时的，不需要 MemoryFlushHook / MemoryMaintenanceHook。
这与 SupervisorService 构建子智能体时加 `.disableMemoryHooks()`（line 555）的原因完全一致——
两者每次 PostCall 都触发额外 LLM 调用与文件推送，对临时蒸馏子智能体既无必要又可能崩溃。

```java
return HarnessAgent.builder()
        .name("skill_distiller")
        .model(model)
        .workspace(workspace)
        .toolkit(tk)
        .sysPrompt(sysPrompt)
        .maxIters(3)
        .enablePendingToolRecovery(true)
        .disableMemoryHooks()   // ← 新增：跳过 MemoryFlushHook + MemoryMaintenanceHook
        .build();
```

### 8.3 子智能体注册了过多默认工具

日志显示 `skill_distiller` 子智能体注册了 `memory_search/get`、`write_file`、`execute`、`agent_list/spawn/send` 等框架默认工具。
这是 HarnessAgent builder 的默认行为——核心工具（文件操作、内存、agent 管理）被自动注入。

**影响**：功能上不影响蒸馏（子智能体只需要调用 `save_skill`），但可能导致 LLM 注意力分散。
**后续优化**：可以在 `buildDistillAgent()` 中通过 `HarnessAgent.builder().disableDefaultTools()` 或类似 API 排除不需要的默认工具（需要确认框架是否支持）。

### 8.4 生成的 SKILL.md 质量问题（已修复）

实际生成的 `quality_query_analysis/SKILL.md` 正文仅 ~20 行，远低于期望的 80-170 行。
与手工编写的 `tool_index/SKILL.md` 和老路径生成的 `defect_density_query/SKILL.md` 对比，
缺少：调用顺序图、每步 JSON 入参/出参示例、参数标准化约束、异常处理、输出格式说明。

**问题 A：重复 YAML frontmatter**

生成的 SKILL.md 包含两段 frontmatter：

```markdown
---
name: quality_query_analysis
description: 处理质量分数据查询的技能     ← LLM 在 content 参数中自带的（不应存在）
---

---
name: quality_query_analysis
description: "处理质量分数据查询的技能"    ← saveSkillWithMetricTag 自动生成的
version: 1
last_evolved_at: 2026-07-08
---
```

**根因**：LLM 在 `save_skill` 的 `content` 参数中包含了 YAML frontmatter，尽管 `@Tool` 注解明确说明"content 是正文部分，不含 frontmatter"。`stripFrontmatter()` 正则只匹配开头第一段 frontmatter，如果 LLM 生成了多段或格式不规范的 frontmatter，可能只去掉了第一段。

**修复**：在 `distillViaSubagent()` 中循环剥离 frontmatter，确保 LLM 生成的所有 frontmatter 段都被清除：

```java
String body = captured.content();
// 循环剥离所有 frontmatter 块 — LLM 可能生成多段或格式不规范的 frontmatter
while (body.startsWith("---")) {
    String stripped = SkillSaveTool.stripFrontmatter(body);
    if (stripped.equals(body)) break;  // 没有更多 frontmatter
    body = stripped;
}
```

**问题 B：正文内容过于简略（已修复）**

**根因分析**：

1. `generate_skill.md` 的正文结构模板只列了 `## 步骤 / ## 输入 / ## 输出 / ## 注意事项` 四个章节，没有要求调用流程、JSON 示例、参数约束、异常处理等。
2. `buildDistillTask()` 的用户消息只说了"把工作流程整理为 SKILL.md 正文"，没有明确要求详细程度。
3. 小参数模型（qwen3:8b）在 maxIters=3 下倾向生成简短内容。

**修复**：

1. 增强 `buildDistillTask()` 用户消息——增加详细的正文结构要求：
   - `## 父智能体派单逻辑`（意图识别 → 参数提取 → agent_spawn 入参示例）
   - `## 子智能体处理步骤`（每步包含工具名、入参 JSON 示例、返回结果格式）
   - `## 调用顺序图`（ASCII 箭头展示调用流）
   - `## 参数标准化约束`
   - `## 异常处理`
   - `## 输出格式`
   - 明确要求正文至少 60 行
   - 强调"不要在 content 中包含 YAML frontmatter"

2. 增强 `generate_skill.md` 正文结构模板——从"建议"改为"必须"，增加上述章节。

**修改清单**：

| # | 文件 | 改动 |
|---|------|------|
| 1 | `SkillSynthesisRunner.java` | `distillViaSubagent()` 中循环剥离 frontmatter；`buildDistillTask()` 增加详细正文结构要求 |
| 2 | `generate_skill.md` | 正文结构模板从"建议"改为"必须"，增加派单逻辑、每步 JSON 示例、调用顺序图、参数约束、异常处理等章节 |

## 9. 验证计划

1. 发送三次相同问题触发自动蒸馏
2. 确认蒸馏走子智能体路径（日志中可见 "skill_distiller" 子智能体被创建）
3. 检查生成的 SKILL.md 是否不包含 thinking 标签
4. 检查 SKILL.md 的工具名称、参数格式是否与实际一致
5. 检查 SKILL.md 是否只有一段 frontmatter（无重复）
6. 检查 SKILL.md 正文行数 ≥ 60 行，包含派单逻辑、JSON 示例、调用顺序图、异常处理
7. 对比改造前后同一问题的蒸馏质量
8. 测试蒸馏失败场景（子智能体未调用 save_skill、maxIters 耗尽）
9. 测试 CAS 防重复（同 fingerprint 不会触发二次蒸馏）
10. 测试回退机制（设置 via-subagent=false 后走原有 SkillDistiller 路径）
11. 测试 PR4 evolve 路径（失败反馈闭环）仍然正常工作