# script_exec 渲染内容接管后抑制 LLM 回复方案

> 状态：设计稿，待评审
> 日期：2026-09-23
> 关联代码：`v2.hooks.ChatScriptExecResultHook`、`v2.hooks.ScriptExecOutputExtractor`、
> `v2.tools.ToolResultRegistry`、`v2.service.impl.ChatStreamServiceImpl`（handleStreamSuccess）

## 1. 背景与根因

`/ai/chat` 链路中，当 `script_exec` 的 stdout 含 ECharts/HTML 渲染块或下载短链时，
`ChatScriptExecResultHook`（ChatScriptExecResultHook.java:108-148）会把**整个工具结果**替换成一句占位符：

```
[系统内部：已接管可渲染内容。请勿复述该内容、生成代码块或输出内部引用 ID；仅根据其余执行结果回答用户。]
```

设计初衷是防 token 膨胀（图表 JSON 体积大）和防模型复述代码块，但副作用是**模型连真实数据也看不到了**：

```
script_exec 返回：  数据表格 + 结论文本 + ```echarts 块 + 下载链接
模型实际看到的：    [系统内部：已接管可渲染内容。……]
模型只能做的：      按工具入参（脚本代码）脑补"图表展示了 XX 趋势……"
```

这是 YY 的结构性根源——事实被工程手段删光了，再怎么用 prompt 劝（"请勿复述"）都不可靠。
本方案改为同样用工程手段，直接让模型对被接管的工具调用**不产生（用户可见的）回复**。

## 2. 现有链路回顾

```
script_exec 执行
   │ PostActingEvent
   ▼
ChatScriptExecResultHook.process()
   │ stdout 含渲染块/下载链接？
   │   是 → 完整 stdout 登记 ToolResultRegistry（fenced 块转 <echarts> 标签）
   │        工具结果整体替换为占位符（本方案要改的点）
   ▼
模型继续推理 → 生成最终回答（当前：基于入参 YY）
   ▼
ChatStreamServiceImpl.handleStreamSuccess（ChatStreamServiceImpl.java:744-751）
   │ finalAnswer = answerContent
   │ finalAnswer = registry.resolveAndAppendCurrentResults(finalAnswer, currentRefs)
   │              （展开本请求 marker + 把注册内容追加到回答末尾）
   ▼
分片 SSE 下发前端 + answerContent 回写（历史刷新可见）
```

关键事实：**模型文本在 handleStreamSuccess 组装后才下发**，该点是天然的机械截断阀；
另外项目已有 `/v2/ai/chat/interrupt` 取消机制（doOnCancel 清理链路）可复用为"提前终止"。

## 3. 方案对比

| | 方案一：回答边界整体抑制 | 方案二：接管即终止（❌已否决） | 方案三：掩码+剥离（说真话路线） |
|---|---|---|---|
| 思路 | 模型照常生成，最终组装时丢弃模型文本，只发系统接管内容 | hook 登记引用后立刻终止 agent 循环，模型根本不生成 | 工具结果只掩码渲染块/链接行，数据保留给模型；回答层剥离模型生成的代码块 |
| 作用范围 | 仅最终回答出口，执行过程不干预 | **终止整个本轮流程**（agent 循环 + 后续所有工具调用） | 执行过程不干预 |
| skill 多工具流程 | 完整保留（script_exec 后仍可继续调其他工具） | **被砍断**：接管后 arith/后续查询等不再执行 | 完整保留 |
| LLM 是否多跑一轮 | 是（浪费一次推理，内网 CPU 模型延迟明显） | 否 | 是 |
| 用户看到的 | 仅系统接管内容（图表/链接/stdout 原文） | 同左 | 模型基于真数据的总结 + 系统接管内容 |
| 改动面 | 极小（handleStreamSuccess 几行） | 中（需适配 SSE/trace/cleanup 状态机） | 小（hook + 回答层两处） |
| YY 是否根除 | 是（模型文本根本不出门） | 是（模型不生成） | 是（有真数据 + 代码块机械剥离） |
| 风险 | 多工具轮次的总结文字也一并丢 | 见 §4.2 | 模型仍可能啰嗦/跑题（但不是编造） |

## 4. 推荐方案：方案一（+变体 A'），方案二已否决

### 4.1 方案一实施要点（回答边界整体抑制）

`handleStreamSuccess` 中，`currentRefs` 非空即视为"本轮发生了渲染内容接管"：

```java
List<String> currentRefs = toolResultRegistry.getRequestRefs(ctx.requestId);
if (!currentRefs.isEmpty()) {
    // 本轮存在系统接管的渲染结果：模型对被接管工具调用的回复属于编造，
    // 整体丢弃，只下发系统接管内容（图表/HTML/下载链接 + stdout 原文）。
    finalAnswer = "";
}
finalAnswer = toolResultRegistry.resolveAndAppendCurrentResults(finalAnswer, currentRefs);
```

`resolveAndAppendCurrentResults` 传入空文本时会把注册内容原样拼出（ToolResultRegistry.java:169-182），
`answerContent` 回写逻辑不变，历史会话刷新看到的就是纯接管内容。

**变体 A'（建议同做，防误伤）**：仅当**被接管的 script_exec 是本轮最后一次工具调用**时抑制。
适用于一轮内先 `query_data` 拿数据、后 `script_exec` 出图的场景——前者的文字总结仍保留。

**A' 的精准性论证**：判断发生在 `handleStreamSuccess`（整轮 agent 循环结束之后），属**事后回看**
而非现场预判——不管 skill 流程里调了多少工具，工具调用序列是既成事实，看最后一项即可。
实施要点与边界：

1. **记录面必须扩展**：hook 目前只记录被接管的调用（`REFERENCES_CTX_KEY`），对非
   script_exec 的 PostActing 直接 return（ChatScriptExecResultHook.java:112）。需改为
   **记录全部工具调用的有序序列**：每次 PostActing 追加 `(toolCallId, toolName, takenOver)`
   到 RuntimeContext；抑制条件 = 序列最后一项是 takenOver=true 的 script_exec；
2. **并行 tool_use**：一轮 LLM 输出多个工具块时完成顺序不定，取"PostActing 完成顺序的
   最后一项"，语义为"此后模型再未调用任何工具"，判定仍然准确；
3. **子 agent 覆盖缺口（现状即如此，非本方案引入）**：`ChatScriptExecResultHook` 只挂主 agent
   hook 链（HarnessAgentPartsConfig.java:169），`SubagentRegistrar` 的子 agent 链没有它。
   skill 流程（FlowCoordinator 驱动主 agent）的全部工具调用都经过该链，A' 判定完整；
   analyze_data 子 agent 直接调 script_exec（SubagentRegistrar.java:208-211 已注册）时
   接管本身不触发（模型能看到完整 stdout），不在本次 YY 问题范围内。若未来把接管扩展到
   子 agent，需同步把序列记录挂到主/子共用的链（如 `ToolCallTrackingHook` 所在位置）。

### 4.2 方案二：接管即终止（❌已否决）

> **结论：否决。** 方案二的作用范围是**终止整个本轮流程**——hook 在 PostActing
> 登记 ref 后取消 agent 循环，不只是"结束这次 LLM 回复"，本轮后续所有工具调用一并停止。

否决原因：skill 流程中 `script_exec` 出图后可能还有后续工具要执行
（如 `arith` 验算、再次查询），接管即终止会把它们全部砍断；而"被接管的 script_exec
是否流程终局步骤"由 LLM 现场决定，工程上没有可靠的预知手段，无法加安全前置条件。
其唯一收益（省一轮模型推理）不足以抵消砍断流程的风险，不再作为后续优化项。

### 4.3 方案三实施要点（备选，"说真话"路线）

若后续发现用户需要图表配套的文字解读，回退到此路线：

1. `ChatScriptExecResultHook`：`modelVisibleOutput` 改为只掩码渲染块和下载链接行
   （替换成中性短标记），stdout 其余数据原文保留给模型；
2. 回答层增加机械剥离：`currentRefs` 非空时，`resolveAndAppendCurrentResults` 之前
   把模型自己生成的 ```echarts / ```html 围栏块和伪造下载链接行整体删掉
   （必须在 append 注册内容**之前**执行，否则误删真实图表）。

## 5. 边界与不改动项

- **download-only 场景**（stdout 只有下载短链无渲染块）：hook 同样接管（ChatScriptExecResultHook.java:128-130），抑制逻辑天然覆盖；
- **SkillJobScheduler / FlowCoordinator 路径**：定时任务报告由模型生成后经 `resolveFinalResult` 落盘，
  不走 `handleStreamSuccess`，本次不改；其工具结果占位符问题同样存在，待该链路单独评估；
- **`{{TOOL_RESULT:xxx}}` marker**：现有 `resolveCurrentMarkers` 已把非本轮 marker 全部移除，无泄漏；
- **空接管内容**：注册内容为空时 `resolveAndAppendCurrentResults` 不追加，维持现有空回答
  告警（EMPTY_MODEL_COMPLETION）路径。

## 6. 测试计划

1. 单测：`handleStreamSuccess` 抑制分支——refs 非空时模型文本不出现在最终 SSE 文本中，
   注册内容完整下发；refs 为空时行为与现状完全一致；
2. 单测（变体 A'）：最后工具调用未被接管时，模型文本保留；
3. E2E（内网）：q2_1 出图场景——前端仅收到图表 + stdout，无模型脑补段落；
   多工具轮次（query_data → script_exec）验证总结保留；
4. 回归：历史会话刷新、interrupt、SSE 超时路径不回归。

## 7. 风险与开放问题

- **用户体验**：用户问"帮我画个图"，回答里只有图没有一句话确认——需要确认这是期望形态
  （当前判断是：图的来源 stdout 本身常含文字说明，足够）；
- **多轮追问**：下一轮用户追问"刚才图里 XX 是多少"，模型上下文里工具结果是占位符，
  仍会脑补——这是占位符机制的固有问题，方案三（掩码保留数据）才能解决该场景，
  可作为方案一之后的补充；
- ~~开放问题：方案二是否值得做~~ → 已否决（见 §4.2：会砍断 skill 流程中 script_exec 之后的工具调用）。
