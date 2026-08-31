# Tool Result 截断中间件方案 (减少 LLM context 累积)

> 创建日期：2026/07/30
> 状态：方案待评审

## 背景

内网 LLM 能力弱，随着 ReAct 流程推进，注入 LLM 的 context 越来越长。最典型的痛点是 `load_skill_through_path`：它返回 SKILL.md 全文（5K+ 字符），主 agent 第一次推理时需要完整内容来规划下一步（spawn 哪个子 agent、传什么参数），但**后续轮次**这个 tool result 已经"用过"，再原样留在 context 里纯属浪费 token。

`ArtifactHandoffHook` 已经把 `wide_table_query` 的大表 CSV 处理成短 handoff 消息，且在 `EXCLUDED_TOOLS` 集合里跳过了 `load_skill_through_path`（因为 SKILL.md 是文档不是业务数据，落 CSV 会误导 python_exec）。所以 `load_skill_through_path` 的结果目前原样进 LLM context，没有任何压缩。

## 目标

第一次 LLM 调用看到完整 SKILL.md（用于规划），后续轮次这个 ToolResultBlock 在 context 里只保留前 200 字符（够 LLM 知道是哪个 skill + 大致做什么），减少 token 累积。

## 核心策略

**"除最后一组外都截断"**：
- 找 `messages` 中**最后一个** `ToolResultBlock` 的位置（即将被本轮 LLM 消费的新结果）-> 不截断
- 该位置**之前**的所有 `ToolResultBlock`，若工具名在配置 map 里 -> 截断到前 N 字符 + 尾巴标记

### 时序示例

```
第 1 轮 LLM 调用前:
  messages = [..., ToolUse(load_skill), ToolResult(5K 全文)]
  最后一个 ToolResult 是 load_skill -> 不截断
  LLM 看完整 SKILL.md -> 决定调 wide_table_query ✅

第 2 轮 LLM 调用前:
  messages = [..., load_skill(5K), ToolUse(wide_table_query), ToolResult(handoff)]
  最后一个 ToolResult 是 wide_table_query -> 不截断
  load_skill 不是最后一个 -> 截断到 200 字符 ✅

第 3 轮 LLM 调用前:
  messages = [..., load_skill(200), wide_table_query(handoff), ToolUse(python_exec), ToolResult(output)]
  最后一个 ToolResult 是 python_exec -> 不截断
  load_skill 截断到 200；wide_table_query 不在配置 map -> 不动 ✅
```

## 实现方案

### 1. 新增 `ToolResultTruncationMiddleware`

文件：`analysis-project/src/main/java/com/agentscopea2a/v2/middleware/ToolResultTruncationMiddleware.java`

实现 `MiddlewareBase.onReasoning`：
- 入参：`ReasoningInput input` (record，含 `List<Msg> messages, List<ToolSchema> tools, GenerateOptions options`)
- 遍历 `input.messages()`，倒序找最后一个含 `ToolResultBlock` 的 Msg 索引 `lastTrIdx`
- 对 `lastTrIdx` 之前的 Msg：
  - 若该 Msg 的 content 里有 `ToolResultBlock`，且 `block.getName()` 在 `toolKeepChars` map 里
  - 取 `block.getOutput()` 中所有 `TextBlock` 拼接成原 text
  - 截断到 N 字符，追加 `\n...(truncated, kept first N chars)`
  - 构造新的 `ToolResultBlock(id, name, List.of(TextBlock.builder().text(truncated).build()), metadata, state)`
  - 用 `Msg.withContent(newContent)` 替换 content（保留原 Msg 的 id/role/metadata/timestamp/usage）
- 构造新的 `List<Msg>` + `new ReasoningInput(newMessages, input.tools(), input.options())`
- 调 `next.apply(newInput)`

字段：
```java
private final Map<String, Integer> toolKeepChars;  // 工具名 -> 保留字符数
private final boolean enabled;
```

### 2. 注册 Spring Bean

文件：`analysis-project/src/main/java/com/agentscopea2a/v2/config/V2InfraConfig.java` (line 233 附近，`sessionMiddleware` bean 后面加)

```java
@Bean
public ToolResultTruncationMiddleware toolResultTruncationMiddleware(
        @Value("${harness.a2a.tool-truncation.enabled:true}") boolean enabled,
        @Value("${harness.a2a.tool-truncation.load_skill_through_path.keep-chars:200}") int loadSkillKeepChars) {
    Map<String, Integer> map = new HashMap<>();
    map.put("load_skill_through_path", loadSkillKeepChars);
    log.info("ToolResultTruncationMiddleware: enabled={}, tools={}", enabled, map);
    return new ToolResultTruncationMiddleware(map, enabled);
}
```

主 agent 通过 `HarnessA2aRunnerV2` line 283 的 `.middlewares(middlewares)` 自动拿到（Spring 把所有 `MiddlewareBase` bean 装成 `List<MiddlewareBase>` 注入构造器 line 92）。

### 3. 子 agent 接线

文件：`analysis-project/src/main/java/com/agentscopea2a/v2/runner/SubagentRegistrar.java` (line 354 附近)

子 agent 不复用主 agent 的 middleware list（line 338 是 `new ArrayList<>()` 单独构造），需显式加入。在 `subMiddlewares.add(subagentEventForwardingMiddleware)` (line 354) 后追加：

```java
if (toolResultTruncationMiddleware != null) {
    subMiddlewares.add(toolResultTruncationMiddleware);
}
```

同时要在 `SubagentRegistrar` 构造器注入 `ObjectProvider<ToolResultTruncationMiddleware>` + 字段（参考 line 139-191 现有 `artifactAccessMiddleware` 范式），并加进 line 199-200 的 null-check 日志。

### 4. 配置

文件：`analysis-project/src/main/resources/application.properties` (或 `application-dev.properties`)

```properties
# Tool result truncation: reduce LLM context bloat from large tool results
# (load_skill_through_path returns full SKILL.md ~5K chars; only the first LLM
# call needs the full text - subsequent rounds keep first 200 chars).
harness.a2a.tool-truncation.enabled=true
harness.a2a.tool-truncation.load_skill_through_path.keep-chars=200
```

## 关键文件清单

| 文件 | 改动 |
|---|---|
| `analysis-project/src/main/java/com/agentscopea2a/v2/middleware/ToolResultTruncationMiddleware.java` | 新增 |
| `analysis-project/src/main/java/com/agentscopea2a/v2/config/V2InfraConfig.java` (line 233 附近) | 加 `@Bean` |
| `analysis-project/src/main/java/com/agentscopea2a/v2/runner/SubagentRegistrar.java` (line 139 构造器、line 199 日志、line 354 挂载) | 注入 + 接线 |
| `analysis-project/src/main/resources/application.properties` | 加 2 个配置项 |

## 关键 API (已验证)

来源 agentscope-core 2.0.0-RC5：

- `io.agentscope.core.middleware.MiddlewareBase.onReasoning(Agent, RuntimeContext, ReasoningInput, Function<ReasoningInput, Flux<AgentEvent>>)` — 拦截点
- `io.agentscope.core.middleware.ReasoningInput` — record `(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options)`
- `io.agentscope.core.message.Msg.withContent(List<ContentBlock>)` — 替换 content，保留其他字段 (Msg.java:663)
- `io.agentscope.core.message.ToolResultBlock(id, name, output, metadata, state)` — 5 参构造器
- `io.agentscope.core.message.TextBlock.builder().text(...).build()` — 构造文本块

## ReActAgent 调用链验证

`ReActAgent.java:1980` 构造 `new ReasoningInput(modelInput, tools, options)` → `MiddlewareChain.build(...).apply(reasoningInput)` → 链中每个 middleware 的 `onReasoning` 拿到的是上一个 middleware 处理后的 input → 最后调 `reasoningCore` 用 `ri.messages()` 发给 LLM。所以我们构造的新 `ReasoningInput` 会传到 LLM，memory 里存的还是原版。

每轮 LLM 调用前 `modelInput` 都从 `event.getInputMessages()`（即 memory）重新组装，所以下一轮又会拿到原版 ToolResultBlock，middleware 再次截断 — 正好符合"第一次完整、后续截断"的语义。

## 参考已有范式

- `ArtifactHandoffHook.applyHandoff()` (line 165-193) — 演示了如何构造 `ToolResultBlock.of(...)` + `Msg.builder().role(TOOL).content(block).build()` 替换 tool result。但我们这里要保留原 Msg 的其他字段，所以用 `Msg.withContent(List<ContentBlock>)` 而不是 builder 重建。
- `SessionMiddleware.onActing()` (line 56-112) — 演示了 v2 middleware 的标准范式：检查是否需要改 → 改 → 构造新 input → `next.apply(newInput)`。

## 验证

1. **编译**：`cd analysis-project && mvn compile`（必须 BUILD SUCCESS）
2. **重启后端**：Spring Boot 不热加载 .class，必须 `taskkill /F` 旧进程 + `mvn spring-boot:run` 重启（参考 memory `backend_restart_after_recompile`）
3. **E2E 测试**：发"杭州二部7月版Q2-1的完成率、达标率是多少? 使用wide_table_q2_1_metrics skills"
   - 期望：主 agent 调 `load_skill_through_path` 后第一轮 LLM 推理看到完整 SKILL.md（决定下一步调 wide_table_query）
   - 后续轮次 LLM 调用日志里 `load_skill_through_path` 的 tool result 只剩 200 字符 + `...(truncated)` 尾巴
   - 最终结果仍正确（完成率 100%、达标率 100%）
4. **日志检查**：在 middleware 加 `log.debug` 打印截断前后的字符数 + 工具名 + toolCallId，便于验证生效
5. **回归**：确保不破坏现有流程 — `load_skill_through_path` 第一次仍完整，`wide_table_query`/`python_exec`/`arith` 不在配置 map 里完全不受影响

## 边界场景

- **一次调多工具**（LLM 并行调 2 个 ToolUseBlock → 2 个 ToolResultBlock 并排）：简化版只保留最后一个不截断，倒数第二个会截断。但 ReAct 场景下罕见，且影响只持续一轮。先不处理。
- **子 agent 调 load_skill_through_path**：子 agent 也挂了 middleware（方案步骤 3），同样截断。
- **截断后 LLM 误判**：200 字符覆盖 SKILL.md frontmatter（name + description），LLM 能识别这是哪个 skill，不会误判。