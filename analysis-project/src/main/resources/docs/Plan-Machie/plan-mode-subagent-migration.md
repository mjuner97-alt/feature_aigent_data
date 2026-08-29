# Plan Notebook 从主 agent 迁移到 analyze_data 子 agent 的方案

> 路径：`src/main/resources/docs/Plan-Machie/plan-mode-subagent-migration.md`
> 关联：`plan-notebook-frontend-design.md`（前端 PlanNotebook 可视化设计）；`process-event-streaming.md`（子 agent 事件透传）
> 日期：2026-07-22（结合框架源码核实后优化）

## 背景与动机

### 用户观察

用户发"分析2026年Q1各部门质量分趋势，生成详细报告"，观察主 agent 的 plan notebook：

```
✓ 步骤1: 派单 analyze_data 查询2026年1季度各部门质量分原始数据   COMPLETED
✓ 步骤2: analyze_data 分析数据 - 统计计算（排序、均值、标准差等）   COMPLETED
✓ 步骤3: analyze_data 生成详细分析报告                              COMPLETED
✓ 步骤4: Supervisor 整理并向用户呈现最终报告                       COMPLETED
```

**问题**：主 agent 就是个路由工具，plan 内容只有 4 步粗粒度的"派单给 analyze_data 做 X"，plan notebook 的结构化跟踪能力没发挥价值。真正复杂的多步推理发生在 `analyze_data` 子 agent 内部（查询数据 -> 统计分析 -> 生成报告），但子 agent 没有 plan mode，中间过程是线性的"我来按顺序执行...先做...现在做..."，无结构化跟踪。

### 核心诉求

- **主 agent 不需要 plan mode** -- 它是路由层，plan 没多少内容，启用 plan mode 反而强制 LLM 走 PLAN -> BUILD 流程，多一轮 LLM 调用开销
- **analyze_data 子 agent 需要 plan mode** -- 它承担真正的多步分析任务，需要结构化 plan + task list 跟踪中间步骤
- **query_data / generate_skill 不需要** -- 任务简单线性（query_data 固定 5 步查询流程；generate_skill 固定 5 步生成流程），plan mode 是过度设计

## 框架源码核实（agentscope-harness 2.0.0-RC5 + agentscope-core 2.0.0-RC5）

### 1. PlanModeManager 每实例独立 ✅

`HarnessAgent.java:2262-2268`（build() 内）：

```java
PlanModeManager planModeManager = null;
if (planModeEnabled) {
    planModeManager = new PlanModeManager(wsManager, planFileDir);
    agentToolkit.registerTool(new PlanModeTools.PlanEnterTool(planModeManager));
    agentToolkit.registerTool(new PlanModeTools.PlanWriteTool(planModeManager));
    agentToolkit.registerTool(new PlanModeTools.PlanExitTool(planModeManager));
    // ... 还会注册 PlanModeMiddleware
}
```

**结论**：每个 `HarnessAgent.build()` 时 `new PlanModeManager(...)`，子 agent 通过 `subagentFactory` 构造时走同一 build() 路径，有自己独立的 PlanModeManager 实例。子 agent 的 plan state 和主 agent 完全隔离。

### 2. planFileDir 默认 "plans"，文件名固定 PLAN.md ✅

`PlanModeManager.java:42, 112-114`：

```java
public static final String DEFAULT_PLAN_DIR = "plans";
// ...
private String defaultPlanFile() {
    return planDir + "/PLAN.md";
}
```

**结论**：如果子 agent 不显式设 `planFileDirectory`，会写 `plans/PLAN.md`。主 agent 当前也写 `plans/PLAN.md`（`HarnessA2aRunnerV2.java:165`）。**Phase 1 后主 agent 移除 plan mode 不再写**，但子 agent 必须显式设不同目录避免未来冲突。

### 3. enablePlanMode() 自动注册 PlanModeMiddleware ✅

`HarnessAgent.java:2274-2281`：

```java
inner.middleware(
        new io.agentscope.harness.agent.middleware.PlanModeMiddleware(
                planModeManager,
                toolName -> { /* 判断只读工具 */ },
                planExtraAllowed));
```

**结论**：`enablePlanMode()` 时框架自动加 `PlanModeMiddleware` 到子 agent 的中间件链，拦截非只读工具调用（plan mode 时只允许 plan_enter/plan_write/plan_exit + 只读工具）。项目不需要手动加这个 middleware。

### 4. PlanExitTool.checkPermissions 返回 ask ✅

`PlanModeTools.java:185-192`：

```java
@Override
public Mono<PermissionDecision> checkPermissions(
        Map<String, Object> toolInput, PermissionContextState context) {
    return Mono.just(
            PermissionDecision.ask(
                    "The agent wants to finish planning and start executing. Approve to"
                            + " continue in BUILD mode, or reject to keep planning."));
}
```

**结论**：子 agent 调 `plan_exit` 会触发 HITL pause。前端无 approval UI，子 agent 会卡住，`agent_spawn` 阻塞直到 600s 超时。**必须反射替换为 `AutoApprovePlanExitTool`**（已有类，主 agent 在用）。

### 5. SubagentDeclaration 不支持 planMode 字段 ✅

`SubagentDeclaration.java:81-102` 字段列表：name, description, workspaceMode, workspacePath, inlineAgentsBody, model, temperature, topP, variant, steps, mode, hidden, persistSession, inheritParentPermissions, exposeToUser, tools, skills, url, headers。**无 planMode 字段**。

`AgentSpecLoader.java:199-323` 只解析固定字段集，frontmatter 里的 `planMode: true` 会被忽略。

**结论**：不能通过 spec frontmatter 驱动 plan mode 开关。两个选择：
- **方案 A（推荐）**：`SubagentRegistrar` 硬编码 `"analyze_data".equals(id)` 判断 -- 简单直接，只有 analyze_data 需要
- 方案 B：项目侧在 `SubagentRegistrar` 重新解析 spec 文件 frontmatter 取 `planMode` 字段 -- 重复解析，不优雅

**选方案 A**。如果未来 query_data 也要 plan mode，改一行代码。

### 6. PlanModeContextState / TaskContextState 不产生 AgentEvent ✅

`PlanModeContextState.java`：普通 POJO，只有 `setPlanActive` / `setCurrentPlanFile` / `isPlanActive` / `getCurrentPlanFile`，无事件发布。

`TaskContextState.java`：普通 POJO，只有 `tasksMutable()` / `getTasks()`，无事件发布。

**结论**：plan mode / task list 状态变化不产生独立 AgentEvent。前端只能通过：
- 轮询 `/v2/ai/session/state` 拿主 agent 的 state（但子 agent state 不在这里）
- 从 SSE 流的 `ToolCallStartEvent(toolName="plan_enter")` / `ToolCallEndEvent(toolName="todo_write")` 推断状态

### 7. 子 agent sessionId 不持久化 ✅

`AgentSpawnTool.java:261-289`：

```java
String sessionId;
if (persistSession) {
    sessionId = "sub-" + hash;       // deterministic
} else {
    sessionId = "sub-" + UUID.randomUUID();  // random
}
```

`SubagentDeclaration.java:236-239`：`persistSession` 默认 false。

**结论**：子 agent 的 sessionId 是 `sub-<random UUID>`，跑完后 AgentState 不持久化到 stateStore。前端无法通过轮询 `/v2/ai/session/state` 拿到子 agent 的 plan/todo state（除非加 `persistSession: true` 到 spec，但这会让子 agent state 跨调用残留，不是我们要的）。

**Phase 2 只能走 SSE 事件流方案**（见下）。

## 现状分析

### 主 agent 当前配置

`HarnessA2aRunnerV2.java:164-166`：

```java
.enablePlanMode()
.planFileDirectory("plans")
.enableTaskList(true)
```

加上 `replacePlanExitWithAutoApprove(agent)`（line 262）反射替换 `plan_exit`。

效果：主 agent 每次接到复杂任务，LLM 先进 PLAN 模式生成 4-5 步粗粒度路由 plan，调 `plan_exit`（被 AutoApprovePlanExitTool 自动批准），进 BUILD 模式开始派单。

### 子 agent 当前配置

`SubagentRegistrar.registerSubagentFromSpec`（line 220-228）：

```java
HarnessAgent.Builder sub = HarnessAgent.builder()
        .name(id)
        .model(model)
        .workspace(workspace)
        .toolkit(tk)
        .sysPrompt(sysPrompt)
        .maxIters(steps)
        .disableSubagents()
        .disableMemoryHooks();
```

**没有**调 `.enablePlanMode()` / `.enableTaskList(true)`，也没有替换 `plan_exit`。

### AGENTS.md 当前引导

`.agentscope/workspace/harness-a2a/AGENTS.md:23-36` 有"第 0 步:判断是否需要进入 Plan Mode"段，引导主 agent LLM 在复杂任务时调 `plan_enter`。**Phase 1 后主 agent 不再启用 plan mode，`plan_enter` 工具不存在，这段必须删，否则 LLM 尝试调用会报错**。

### 前端已有能力

`V2SessionController.getState()`（line 116-181）已实现 `GET /v2/ai/session/state`，从主 agent 的 `AgentState` 读 `PlanModeContextState` + `TaskContextState`，返回 `SessionStateResponse`。

`SubagentEventForwardingMiddleware` 已把子 agent 的 `AgentEvent` 镜像到父 emitter，前端 SSE 流能看到子 agent 的 `text_block_delta` / `tool_call_start` 等事件，`event.source` 标记子 agent 名。

## 方案设计

### Phase 1：后端核心改动

#### 1.1 主 agent 移除 plan mode

**文件**：`src/main/java/com/agentscopea2a/v2/runner/HarnessA2aRunnerV2.java`

改动：
- 删除 `.enablePlanMode()`（line 164）
- 删除 `.planFileDirectory("plans")`（line 165）
- 删除 `.enableTaskList(true)`（line 166）
- 删除 `replacePlanExitWithAutoApprove(this.agent)` 调用（line 262）
- 把 `replacePlanExitWithAutoApprove` 方法从 `private static` 改为 `public static`，供 `SubagentRegistrar` 复用
  - 方法签名不变，只是可见性改
  - `AutoApprovePlanExitTool.java` 保留（子 agent 要用）

主 agent 变成纯路由：接到任务 -> 直接 ReAct 循环 -> `agent_spawn(analyze_data)` 派单。省一轮 PLAN -> BUILD 的 LLM 调用。

#### 1.2 AGENTS.md 同步删除 Plan Mode 段

**文件**：`.agentscope/workspace/harness-a2a/AGENTS.md` + `src/main/resources/workspace/AGENTS.md`

删除 line 23-36 的"第 0 步:判断是否需要进入 Plan Mode"整段。主 agent 的 prompt 不再引导 LLM 进 plan mode。

保留"第 1 步:路由决策"和"第 2 步:数值计算硬规则"（这些是路由逻辑，与 plan mode 无关）。

#### 1.3 analyze_data 子 agent 启用 plan mode

**文件**：`src/main/java/com/agentscopea2a/v2/runner/SubagentRegistrar.java`

`registerSubagentFromSpec` 方法里，`subagentFactory` lambda 内，对 `analyze_data` 加 plan mode 配置：

```java
parent.subagentFactory(agentId, id -> {
    Toolkit tk = new Toolkit();
    // ... 现有工具注册逻辑不变 ...

    HarnessAgent.Builder sub = HarnessAgent.builder()
            .name(id)
            .model(model)
            .workspace(workspace)
            .toolkit(tk)
            .sysPrompt(sysPrompt)
            .maxIters(steps)
            .disableSubagents()
            .disableMemoryHooks();

    // ★ 新增：analyze_data 启用 plan mode
    boolean enablePlan = "analyze_data".equals(id);
    if (enablePlan) {
        sub.enablePlanMode()
          .planFileDirectory("plans/subagents/" + id)  // plans/subagents/analyze_data/
          .enableTaskList(true);
    }

    SandboxFilesystemSpec fs = sandboxFsProvider != null ? sandboxFsProvider.getIfAvailable() : null;
    if (fs != null) {
        sub.filesystem(fs);
    }

    // ... 现有 hooks/middlewares 逻辑不变 ...

    HarnessAgent built = sub.build();

    // ★ 新增：替换 plan_exit 为 AutoApprovePlanExitTool
    if (enablePlan) {
        HarnessA2aRunnerV2.replacePlanExitWithAutoApprove(built);
    }

    log.debug("Built subagent '{}' with tools={} planMode={} ...",
            id, registered, enablePlan, ...);
    return built;
});
```

**planFileDirectory 用 `plans/subagents/<id>`** 的理由：
- `PlanModeManager` 会写 `<planDir>/PLAN.md`（`PlanModeManager.java:113`）
- 子 agent 写 `plans/subagents/analyze_data/PLAN.md`
- 主 agent Phase 1 后不再写 plan 文件
- 未来如果其他子 agent 也启用，各自独立目录（`plans/subagents/query_data/PLAN.md`）不冲突

#### 1.4 analyze_data spec prompt 更新

**文件**：`src/main/resources/workspace/agent-subagents/analyze_data.md` + `.agentscope/workspace/harness-a2a/agent-subagents/analyze_data.md`

frontmatter `maxIters: 8` 改 `12`（plan_enter + plan_exit + 多次 todo_write 占迭代）。

工作流程段（line 24-29）加 plan mode 引导：

```markdown
## 工作流程
1. **理解需求** - 明确用户要做什么分析（趋势 / 对比 / 归因 / 报告生成）
2. **判断是否需要 plan** - 复杂多步任务（查询 + 计算 + 报告）先调 `plan_enter` 制定结构化计划,
   简单查询（单步取数）直接 ReAct 不用 plan
3. **制定 plan**（如需）- 调 `plan_write` 写计划,再调 `plan_exit` 进 BUILD 模式
4. **执行 plan** - 按 plan 顺序执行,每步用 `todo_write` 跟踪状态
5. **取数** - 通过 `tool_router` 调用质量查询工具获取数据
6. **算数** - 见下面「数据处理决策树」★ **必做,不能跳过**
7. **解读** - 把数字翻译成业务结论
```

注意：不是所有 analyze_data 调用都需要 plan mode。简单查询 LLM 直接 ReAct 即可，复杂分析才进 plan mode。spec prompt 要引导 LLM 区分。

### Phase 2：前端适配

#### 2.1 约束确认（基于框架源码核实）

- **子 agent state 不持久化**（`persistSession=false` 默认）-- 前端轮询 `/v2/ai/session/state` 拿不到子 agent 的 plan/todo
- **PlanModeContextState / TaskContextState 不产生 AgentEvent** -- 前端无法从 SSE 流拿到 "plan mode 进入" 这类独立事件
- **唯一信号源**：`ToolCallStartEvent` / `ToolCallEndEvent`，`toolName` 字段为 `plan_enter` / `plan_write` / `plan_exit` / `todo_write`

#### 2.2 方案：前端从 SSE 流的 ToolCall 事件推断子 agent plan/todo

`SubagentEventForwardingMiddleware` 已经把子 agent 的 `AgentEvent`（含 `ToolCallStartEvent` / `ToolCallEndEvent`）镜像到父 emitter，前端 SSE 流能看到，`event.source = "analyze_data"`。

前端逻辑：
1. 识别 `event.source = "analyze_data"` 且 `toolName = "plan_enter"` 的 `ToolCallStartEvent` -> PlanPanel 标记 analyze_data 进入 PLAN 模式
2. 识别 `toolName = "plan_exit"` 的 `ToolCallEndEvent` -> PlanPanel 标记进入 BUILD 模式
3. 识别 `toolName = "todo_write"` 的 `ToolCallEndEvent` -> 从 `ToolResultBlock` 解析 task 列表更新 TodoListPanel
4. 识别 `toolName = "plan_write"` 的 `ToolCallEndEvent` -> 从 `ToolResultBlock` 拿 plan 文件路径,调 `/v2/ai/session/plan?file=...` 读 plan 内容（需新增端点）

**风险**：`todo_write` 的 `ToolResultBlock` 是否包含完整 task 列表？需要看框架 `TodoTools` 的实现。如果只返回 "OK" 之类的确认文本，前端拿不到 task 列表，需要加额外端点读子 agent state（但子 agent state 在内存只在 agent_spawn 期间有效）。

**备选**：如果 `todo_write` 的 ToolResultBlock 不含 task 列表，Phase 2 走轮询 + `persistSession: true`：
- `analyze_data.md` spec frontmatter 加 `persistSession: true`
- 子 agent state 持久化到 MySQL,sessionId = `sub-<hash(parentSessionId, agentId, label)>` deterministic
- 加新端点 `GET /v2/ai/session/subagent/state?userId=X&parentConversationId=Y&subAgentName=analyze_data`
- 从 stateStore 读子 agent state

但 `persistSession: true` 会让子 agent state 跨调用残留（下次同 parent + 同 label 的 spawn 会复用 state），可能不是我们要的。需要 E2E 验证。

#### 2.3 前端组件改造

`plan-notebook-frontend-design.md` 设计的 `PlanPanel` / `TodoListPanel` 改为：
- `PlanPanel` 展示子 agent 的 plan（从 `plan_write` 的 ToolResult 拿文件路径 + 新端点读内容）
- `TodoListPanel` 展示子 agent 的 tasks（从 `todo_write` 的 ToolResult 解析,或轮询子 agent state）
- `StateMachineView` 加子 agent plan mode 状态卡片
- `event.source` 区分是哪个子 agent（当前只有 analyze_data,但设计上支持多子 agent）

## 改动文件清单

### Phase 1（后端核心）

| 文件 | 改动 |
|---|---|
| `v2/runner/HarnessA2aRunnerV2.java` | 删除 `.enablePlanMode()` / `.planFileDirectory("plans")` / `.enableTaskList(true)`（line 164-166）；删除 `replacePlanExitWithAutoApprove(this.agent)` 调用（line 262）；`replacePlanExitWithAutoApprove` 方法从 `private static` 改 `public static` |
| `v2/runner/SubagentRegistrar.java` | `subagentFactory` lambda 内对 `analyze_data` 加 `.enablePlanMode()` + `.planFileDirectory("plans/subagents/" + id)` + `.enableTaskList(true)`；`sub.build()` 后调 `HarnessA2aRunnerV2.replacePlanExitWithAutoApprove(built)` |
| `workspace/agent-subagents/analyze_data.md`（src + .agentscope 两份） | frontmatter `maxIters: 8` 改 `12`；工作流程段加 plan mode 引导 |
| `workspace/AGENTS.md`（src + .agentscope 两份） | 删除"第 0 步:判断是否需要进入 Plan Mode"段（line 23-36） |

### Phase 2（前端适配，方案待 E2E 验证后定）

| 文件 | 改动 |
|---|---|
| `v2/controller/V2SessionController.java` | 新增 `GET /v2/ai/session/plan?file=...` 读 plan 文件内容（子 agent 写到 `plans/subagents/<id>/PLAN.md`） |
| `frontend/src/components/PlanPanel.tsx` | 从 SSE 流识别 `plan_enter`/`plan_exit`/`plan_write` 事件,展示子 agent plan |
| `frontend/src/components/TodoListPanel.tsx` | 从 `todo_write` ToolResult 解析 task 列表 |
| `frontend/src/components/StateMachineView.tsx` | 加子 agent plan mode 状态卡片 |

## 关键问题与风险

### 1. 子 agent plan_exit 的 HITL pause（已确认 + 已有解）

**风险**：`PlanModeTools.PlanExitTool.checkPermissions` 返回 `ask`（`PlanModeTools.java:185-192`）。子 agent 调 `plan_exit` 会卡 HITL pause。

**应对**：`sub.build()` 后调 `HarnessA2aRunnerV2.replacePlanExitWithAutoApprove(built)`（已有方法，主 agent 在用）。反射拿 `planModeManager` 私有字段，`toolkit.removeTool("plan_exit")` + `registerTool(new AutoApprovePlanExitTool(planModeManager))`。

### 2. plan 文件路径（已确认无冲突）

**原担心**：主 agent 和子 agent 都写 `plans/PLAN.md` 冲突。

**实际**：
- Phase 1 后主 agent 不再启用 plan mode，不写 plan 文件
- 子 agent 用 `plans/subagents/analyze_data/PLAN.md`
- 路径隔离充分

### 3. AGENTS.md 必须同步删除 Plan Mode 段（新增风险）

**风险**：`AGENTS.md:23-36` 的"第 0 步:判断是否需要进入 Plan Mode"段引导主 agent LLM 调 `plan_enter`。Phase 1 后主 agent 的 `plan_enter` 工具不存在（因为 `enablePlanMode()` 被删），LLM 尝试调用会报 "unknown tool" 错误。

**应对**：必须同步删除 AGENTS.md 的 Plan Mode 段。**两份文件都要改**：
- `src/main/resources/workspace/AGENTS.md`（源文件）
- `.agentscope/workspace/harness-a2a/AGENTS.md`（materialized 副本,运行时读的是这份）

如果不改 materialized 副本,光改源文件,JVM 重启后才生效（`WorkspaceMaterializer.ensureMaterialized` 会重新 copy）。

### 4. maxIters 不够（已确认）

**风险**：plan_enter / plan_exit / plan_write / todo_write 是普通工具调用，占 ReAct 迭代。当前 `maxIters: 8` 不够。

**应对**：`analyze_data.md` 的 `maxIters` 从 8 改 12。典型迭代消耗：
- plan_enter（1 轮）
- plan_write（1 轮）
- plan_exit（1 轮）
- todo_write（1-2 轮）
- 查询数据（1-2 轮）
- 统计计算（1-2 轮）
- todo_write 更新（2-3 轮）
- 生成报告（1 轮）

合计 9-13 轮，12 应该够，不够再调 15。

### 5. LLM 不主动进 plan mode（软约束）

**风险**：启用 plan mode 不等于 LLM 一定会调 `plan_enter`。LLM 可能直接进 ReAct 循环跳过 plan。

**应对**：`analyze_data.md` spec prompt 加引导段（见 1.4）。但这是软约束，LLM 可能不遵守。E2E 验证 LLM 是否按预期进 plan mode。

### 6. todo_write 的 ToolResult 是否含 task 列表（Phase 2 待核实）

**风险**：如果框架的 `TodoTools` 返回的 ToolResultBlock 只是 "OK" 确认文本,不含完整 task 列表,前端无法从 SSE 流解析 task 状态。

**应对**：Phase 2 前先 E2E 打印 `todo_write` 的 ToolResultBlock 内容。如果不含 task 列表,走备选方案（`persistSession: true` + 新端点轮询）。

### 7. 简单查询被强制 plan mode（软约束）

**风险**：如果 `analyze_data.md` 的 plan mode 引导太强,LLM 对"查 Q1 一部缺陷密度"这种简单查询也进 plan mode,浪费 2-3 轮迭代。

**应对**：spec prompt 明确"简单查询不用进 plan mode,复杂多步分析才进"。E2E 测试覆盖两种场景。

## 验证步骤

### Phase 1 验证

#### 1. 编译 + 启动
```bash
cd D:/AILLMS/javacode/analysis-project/analysis-project
mvn clean package -Dmaven.test.skip=true 2>&1 | tail -10
cmd.exe //c "taskkill /F /IM java.exe" 2>/dev/null
DOCKER_API_VERSION=1.46 DOCKER_HOST=ssh://root@116.148.120.160 \
  nohup G:/jdk21/bin/java.exe -jar target/analysis-project-0.0.1-SNAPSHOT.jar \
  --spring.profiles.active=sandbox-windows,dev > /tmp/app-plan-subagent.log 2>&1 &
sleep 25
```

#### 2. 主 agent 不再进 plan mode
```bash
curl -sN -X POST http://localhost:8081/v2/ai/chat \
  -H 'Content-Type: application/json' \
  --data-binary '{"input":"分析2026年Q1各部门质量分趋势,生成详细报告","conversationId":"plan-sub-001","user_id":"plan_tester"}' \
  > /tmp/plan-sub-main.txt 2>&1

# 预期: 无 plan_enter / plan_exit / plan_write 工具调用
grep -c "plan_enter\|plan_exit\|plan_write" /tmp/plan-sub-main.txt  # 预期: 0
# 预期: 直接 agent_spawn(analyze_data)
grep -c "agent_spawn" /tmp/plan-sub-main.txt                        # 预期: ≥1
```

#### 3. analyze_data 子 agent 进 plan mode
```bash
# 从 SSE 流里看 analyze_data 的事件 (source=analyze_data)
grep -E '"source":"analyze_data".*plan_enter|"source":"analyze_data".*plan_exit|"source":"analyze_data".*todo_write' /tmp/plan-sub-main.txt | head -20
# 预期: 复杂任务能看到 plan_enter + plan_write + plan_exit + 多次 todo_write
```

#### 4. 子 agent plan_exit 不卡住
```bash
# 整个请求在合理时间内完成（不 600s 超时）
grep -c "^event:done$" /tmp/plan-sub-main.txt  # 预期: 1
```

#### 5. plan 文件写到正确目录
```bash
# 本地 host (如果 sandbox filesystem 写到本地)
ls .agentscope/workspace/harness-a2a/plans/subagents/analyze_data/ 2>/dev/null
# 预期: 有 PLAN.md

# 或容器内 (sandbox 模式)
ssh docker-host "docker exec agentscope-shared-demo ls /workspace/plans/subagents/analyze_data/ 2>/dev/null"
# 预期: 有 PLAN.md

# 确认主 agent 不再写 plans/PLAN.md
ls .agentscope/workspace/harness-a2a/plans/PLAN.md 2>/dev/null
# 预期: 不存在 (或文件是旧的)
```

#### 6. 简单查询不被强制 plan mode
```bash
curl -sN -X POST http://localhost:8081/v2/ai/chat \
  -H 'Content-Type: application/json' \
  --data-binary '{"input":"查询2026年1季度杭州开发一部的质量分","conversationId":"plan-sub-002","user_id":"plan_tester"}' \
  > /tmp/plan-sub-simple.txt 2>&1

# 预期: 派单 query_data (不是 analyze_data)
grep -c "agent_spawn.*query_data" /tmp/plan-sub-simple.txt  # 预期: ≥1
# 预期: query_data 不进 plan mode (没启用)
grep -c "plan_enter" /tmp/plan-sub-simple.txt               # 预期: 0
```

#### 7. 回归：interrupt 端点仍可用
```bash
curl -sN -X POST http://localhost:8081/v2/ai/chat \
  -H 'Content-Type: application/json' \
  --data-binary '{"input":"分析2026年Q1各部门质量分趋势","conversationId":"plan-sub-int-001","user_id":"plan_int_tester"}' \
  > /tmp/plan-sub-int1.txt 2>&1 &
sleep 8
curl -sN -X POST http://localhost:8081/v2/ai/chat/interrupt \
  -H 'Content-Type: application/json' \
  --data-binary '{"user_id":"plan_int_tester","conversationId":"plan-sub-int-001","supplement":"改成按产品线分组"}' \
  > /tmp/plan-sub-int2.txt 2>&1
grep -c "^event:done$" /tmp/plan-sub-int2.txt  # 预期: 1
```

#### 8. todo_write ToolResult 内容核实（Phase 2 前置）
```bash
# 打印 todo_write 的 ToolResultBlock,看是否含 task 列表
grep -A5 "todo_write" /tmp/plan-sub-main.txt | grep -E "task|todo|subject|state" | head -10
# 如果只返回 "OK" 不含 task 列表 -> Phase 2 走 persistSession 备选方案
# 如果返回完整 task 列表 -> Phase 2 走 SSE 事件流方案
```

### Phase 2 验证（待方案定后细化）

- 前端 PlanPanel 能展示 `analyze_data` 子 agent 的 plan 内容
- 前端 TodoListPanel 能展示子 agent 的 task 列表 + 状态切换
- 子 agent 跑完后 plan/todo 消失或标记为完成

## 不做的事（明确跳过）

- ❌ 不给 `query_data` / `generate_skill` 启用 plan mode（任务简单线性，不需要）
- ❌ 不做子 agent state 持久化到 MySQL（`persistSession` 保持默认 false；Phase 2 备选方案才考虑）
- ❌ 不做子 agent 内部中断恢复（[[interrupt_resume_endpoint_verified]] 已记录为已知限制）
- ❌ 不改框架 `SubagentDeclaration` / `AgentSpecLoader` 加 `planMode` 字段（硬编码在 SubagentRegistrar,简单直接）
- ❌ 不做主 agent plan mode 的配置开关（直接移除,不保留可配置项 -- YAGNI）
- ❌ 不做 plan 文件跨用户隔离（`plans/subagents/<id>/` 目录在 shared workspace 下,但 plan 内容是 LLM 生成的任务计划,不含用户数据；sandbox filesystem 的 IsolationScope 已提供 namespace 隔离）

## 关联文档与记忆

- `plan-notebook-frontend-design.md` -- 前端 PlanNotebook 可视化设计（Phase 2 基础）
- `process-event-streaming.md` -- 子 agent 事件透传机制（SubagentEventForwardingMiddleware 设计）
- [[interrupt_resume_endpoint_verified]] -- interrupt 端点已验证，Phase 1 后需回归
- [[plan_state_cross_restart_verified]] -- plan_mode_context 持久化机制（主 agent 的,子 agent 不持久化）
- [[spec_prompt_strengthening]] -- AGENTS.md 的 Plan Mode 触发段（本方案要移除）
- [[subagent_hook_chain_migration]] -- 子 agent hook 链不继承主 agent（本方案在 SubagentRegistrar 显式挂 plan mode）
- [[permission_mode_endpoint]] -- PermissionMode 切换端点（bypass 模式可替代 plan_exit 替换,但本方案保留 AutoApprovePlanExitTool 方案）
- [[workspace_materializer_no_delete]] -- materialized 副本需同步改（AGENTS.md / analyze_data.md 两份都要改）
