# ADR: 新增 ArithTool -- 杜绝主子智能体心算

> Date: 2026-07-18
> Status: Accepted & E2E Verified
> Context: v2 升级到 agentscope-java 2.0.0-RC5 后，内网部署的小参数 LLM (glm-5.2) 在看似简单的心算上频繁出错

## Decision

**新增 `ArithTool`：单工具 `arith(op, numbers[])`，5 个 op (add/sub/mul/div/pct)，内部用 `BigDecimal` 精确计算。除 `generate_skill` 外所有 agent（supervisor / query_data / analyze_data）强制挂载。AGENTS.md 硬规则表置顶新增"任何算术 -> 强制走 arith"行；"可以自己算"段删加减乘除例外，仅保留"显而易见的比较"（比较只看正负，不是算术）。**

## Rationale

### 问题：小参数 LLM 心算不可靠

内网用的 glm-5.2 在看似简单的心算上也会出错。典型案例：

| 输入 | LLM 心算结果 | 正确结果 |
|---|---|---|
| `23.1 - 13.1` | `11.0` | `10.0` |
| `23.1 + 13.1 + 5.0` | `41.20000001` (浮点误差) | `41.2` |
| `23 / 100 * 100%` | `22%` 或 `0.23%` | `23%` |

当前架构里 `AGENTS.md` 第 75-78 行明确允许"单个减法 / ≤3 个数字的求和 / 显而易见的比较"心算，`analyze_data.md` 决策树第一条也允许"简单两三个数加减/比较 -> ✅ 自己算"。**这些例外正是小参数 LLM 出错的源头**。

### 方案：把"禁止心算"从 prompt 规则升级为机械保证

prompt engineering 单独不够 -- LLM 训练数据偏好心算，规则写在 prompt 里也会被忽略。新增 `ArithTool` 把所有加减乘除/百分比运算强制走工具：

- LLM 要么调 `arith`，要么不算 -- 没有"我脑内算一下"这条路径
- `BigDecimal` 在 JVM 层消除浮点误差（`23.1 + 13.1 = 41.2`，不是 `41.20000001`）
- 工具描述里明确写"禁止心算"，与 AGENTS.md 硬规则双保险

### 为什么是 BigDecimal 不是 double

`23.1 + 13.1` 用 `double` 得 `36.200000000000004`，`BigDecimal.valueOf(23.1).add(BigDecimal.valueOf(13.1))` 得 `36.2`。这正是 LLM 心算错的同类问题（精度误差），用 BigDecimal 一次根除。

`BigDecimal.valueOf(double)` 内部用 `Double.toString`，给出短十进制表示（`23.1 -> "23.1"`），避免 `new BigDecimal(23.1)` 的二进制展开陷阱（`23.099999999999998...`）。

### 为什么 arith 不进 tool_router

`arith` 直接注册到 agent 的 Toolkit（像 `python_exec` / `skill_save` 一样），不走 `router_tool(paramsJson)` 元工具。原因：

- arith 只 2 个参数（op + numbers），元工具路由反而更慢
- LLM 一步调到位，不必先 `toolMetaInfo(toolId="arith")` 再 `router_tool(paramsJson=...)`
- arith 是常驻工具（每个 agent 都可能用），不是业务工具（按需路由）

### 为什么保留"显而易见的比较"心算

比较只看正负（`23.1 > 13.1`），不涉及算术，小 LLM 不易错。强制走工具反而徒增 LLM 调用轮次。这是设计上的折中：算术必须走工具，比较可以心算。

## Changes

### 新增

| 文件 | 说明 |
|---|---|
| `src/main/java/com/agentscopea2a/v2/tools/ArithTool.java` | 单工具 `arith(op, numbers[])`，5 个 op (add/sub/mul/div/pct)，BigDecimal 精确计算，纯 JVM 无沙箱依赖 |

### 修改

| 文件 | 改动 |
|---|---|
| `src/main/java/com/agentscopea2a/v2/config/V2ToolConfig.java` | 新增 `arithTool()` bean；`v2ToolGroupAdapter()` 注册 ArithTool 为 **ungrouped**（关键：不能进 group，详见 #关键发现） |
| `src/main/java/com/agentscopea2a/v2/runner/SubagentRegistrar.java` | 构造函数加 `ObjectProvider<ArithTool>` 参数；`toolRegistry.put("arith", at)` 让子 agent 能声明 |
| `src/main/resources/workspace/agent-subagents/query_data.md` | front matter `tools: tool_router` -> `tools: [tool_router, arith]` |
| `src/main/resources/workspace/agent-subagents/analyze_data.md` | front matter `[tool_router, python_exec]` -> `[tool_router, python_exec, arith]`；决策树第一条"自己算"改成 `arith(op="...", numbers=[...])`；保留"显而易见的比较"心算 |
| `src/main/resources/workspace/AGENTS.md` | 硬规则表置顶新增"任何算术 -> 强制走 arith"行；"可以自己算"段删加减乘除例外，只留比较；违反代价段加 arith |
| `src/main/java/com/agentscopea2a/v2/hooks/ArtifactHandoffHook.java` | `EXCLUDED_TOOLS` Set 加 `"arith"`，防单数字结果被误当表格解析 |

`generate_skill.md` 不动（不参与计算）。

## API 设计

```java
@Tool(
    name = "arith",
    description = "精确算术运算。所有加减乘除/百分比必须走此工具,禁止心算。"
        + "op=add 求和; op=sub 第一个数减后面所有数; op=mul 求积; "
        + "op=div 第一个数除以后面所有数; op=pct numbers[0]/numbers[1]*100。"
        + "内部用 BigDecimal,无浮点误差。"
        + "返回单行 result,不是表格。")
public ToolResultBlock arith(
    @ToolParam(name = "op", description = "运算: add / sub / mul / div / pct") String op,
    @ToolParam(name = "numbers", description = "数字列表。add/mul 至少 1 个; sub/div 至少 2 个; pct 恰好 2 个") List<Double> numbers)
```

### 5 个 op 语义

| op | 语义 | 示例 |
|---|---|---|
| `add` | 全部求和 | `[23.1, 13.1, 5.0]` -> `41.2` |
| `sub` | 第一个减后面所有 | `[23.1, 13.1]` -> `10`；`[10, 3, 2]` -> `5` |
| `mul` | 全部求积 | `[2.5, 4]` -> `10` |
| `div` | 第一个除以后面所有 | `[100, 5, 2]` -> `10`；除数为 0 返回错误消息 |
| `pct` | `numbers[0] / numbers[1] * 100` | `[23, 100]` -> `23`；总数为 0 返回错误消息 |

### 输出格式

```
[arith] op=sub numbers=[23.1, 13.1]
result: 10
```

`formatResult` 用 `stripTrailingZeros` + `toPlainString` 去尾零、避免科学计数法（`41.20000 -> "41.2"`、`10.0 -> "10"`、`0.0000001 -> "0.0000001"`）。

### 错误处理

错误时返回 `ToolResultBlock.text("arith 拒绝执行: " + msg)` 而**不抛异常**。LLM 看到错误消息能自纠（如 op 拼错、operand 数量不符、除数为 0）。

## 关键发现：V2ToolGroupAdapter 的 group 机制对常驻工具是阻断性的

### 问题

最初实现把 arith 注册到 `'arith'` group：

```java
b.createGroup("arith", "Inline arithmetic for any number of operands (BigDecimal)", true)
 .tool(at, "arith");
```

`active=true` 看似应该激活，但 E2E 测试发现 **LLM 的工具列表里根本没有 `arith`**。LLM 被迫用 harness 内置的 `execute` (sandbox) 写 Python 代码算 `23.1 - 13.1`，完全违背设计意图。

### 根因

通过 grep OpenAI client 日志确认 LLM 实际收到的工具列表：

```
agent_list, agent_send, agent_spawn, edit_file, execute, glob_files, grep_files,
list_files, load_skill_through_path, memory_get, memory_save, memory_search,
plan_enter, plan_exit, plan_write, propose_skill, read_file, reset_equipped_tools,
session_history, session_list, session_search, skill_manage, task_cancel, task_list,
task_output, todo_write, wait_async_results, write_file
```

**只有 ungrouped tools + `reset_equipped_tools` meta-tool 可见**。`arith` 和 `python_exec`（都在 group 里）不可见，尽管 `reset_equipped_tools` 的 description 说它们是 "Activated"。

V2ToolGroupAdapter 的设计本意是 LLM 通过 `reset_equipped_tools(to_activate=[...])` 主动激活 group。但 glm-5.2 不会自然这么做 -- 它看到 `execute` 可用就直接用，不会想到先激活 `python_exec` group。对 `arith` 更糟糕：没有替代工具，LLM 只能写 Python 算加减法。

### 修复

把 arith 注册从 grouped 改成 ungrouped：

```java
// 旧
b.createGroup("arith", "Inline arithmetic for any number of operands (BigDecimal)", true)
 .tool(at, "arith");

// 新
b.tool(at);  // ungrouped, always available
```

子 agent 不受此问题影响 -- `SubagentRegistrar` 用 `tk.registerTool(tool)`（ungrouped），天然可见。

### 影响范围

- `python_exec` 仍在 group 里，但 LLM 用 harness 内置 `execute` 替代，功能不受影响
- `arith` 改为 ungrouped 后始终可见，LLM 直接调用
- `reset_equipped_tools` meta-tool 仍有作用（切换 `python_exec` group），但对 arith 不再需要

### 后续建议

V2ToolGroupAdapter 的 group 机制本身存在 bug 或设计缺陷：`active=true` 的 group tools 应该被发送给 LLM，但实际没有。如果未来要让 group 机制真正可用，需要排查 harness 的 `Toolkit.createToolGroup` 实现。本次不做 -- arith 改为 ungrouped 是最小修复。

## E2E 验证

> 验证日期：2026/07/18 12:42-12:56
> 启动命令：`mvn package -Dmaven.test.skip=true` + `java -jar target/analysis-project-0.0.1-SNAPSHOT.jar --spring.profiles.active=sandbox-windows,dev`
> 启动日志：`ArithTool: wired (BigDecimal-backed inline arithmetic)` + `SubagentRegistrar: toolRegistry built with 4 entries: [python_exec, arith, skill_save, tool_router]`

### 启动验证

| 项 | 预期 | 实际 | 状态 |
|---|---|---|---|
| ArithTool bean 创建 | `ArithTool: wired` | `12:42:20.469 ArithTool: wired (BigDecimal-backed inline arithmetic)` | ✅ |
| 主 agent toolkit 装配 | `Toolkit wired (3 tools)` | `12:42:20.478 Toolkit wired (3 tools, groups: [python_exec])` | ✅ |
| 子 agent toolRegistry 含 arith | 4 entries 含 arith | `toolRegistry built with 4 entries: [python_exec, arith, skill_save, tool_router]` | ✅ |
| fail-fast 校验通过 | 无 unknown tool 报错 | 启动成功，无报错 | ✅ |
| arith 在 LLM 工具列表 | ungrouped 可见 | grep OpenAI request 含 `"name":"arith"` | ✅ |

### 用例验证

| # | 用例 | 输入 | 预期 | 实际 | 状态 |
|---|---|---|---|---|---|
| 1 | 简单减法 | "23.1、13.1，差多少？" | `arith(op=sub, [23.1, 13.1]) -> 10` | `12:43:00.224 arith: op=sub numbers=[23.1, 13.1] -> 10`；LLM 回复"差值 10.0" | ✅ |
| 2 | 多 operand 求和 | "23.1、13.1、5.0 总和" | `arith(op=add, [23.1, 13.1, 5.0]) -> 41.2` | `12:47:18.659 arith: op=add numbers=[23.1, 13.1, 5.0] -> 41.2`（BigDecimal 无浮点误差） | ✅ |
| 3 | 百分比 | "23 个占 100 个百分之多少" | `arith(op=pct, [23, 100]) -> 23` | `12:49:56.547 arith: op=pct numbers=[23.0, 100.0] -> 23`；LLM 回复"23%" | ✅ |
| 4 | 复杂分析仍走 data_primitives | "分析2026年1季度各部门质量分分布" | 未调 arith，走 data_distribution | arith 调用次数=0；analyze_data 子 agent 调 `router_tool(data_distribution)` | ✅ |
| 5 | Handoff 不误触发 | arith 调用后 | result 是单行文本，无 CSV artifact | arith result_len=46-53（小文本），无 `📦 完整数据已保存为 CSV artifact` 消息 | ✅ |

### LLM 行为验证

测试 1 的 LLM 回复开头：
> "根据硬规则,任何加减乘除都必须走 `arith` 工具,我来计算一下。"

证明 AGENTS.md 硬规则成功注入并影响 LLM 行为。

## 风险与回滚

### 风险

| 风险 | 缓解 |
|---|---|
| LLM 不调 arith 仍心算 | AGENTS.md 硬规则 + 工具描述"禁止心算"双保险。E2E 验证 glm-5.2 在 5 个用例中都正确调 arith。若高频出错可加 `ArithEnforceHook` 在 PostCall 检测回复数字是否经 arith（实现复杂，本次不做） |
| arith 工具列表膨胀 | supervisor 现挂 3 个工具（python_exec + arith + reset_equipped_tools），LLM 工具列表总长 ~28 个，arith 描述清晰不造成 routing 困扰 |
| `ObjectProvider<ArithTool>` 注入失败 | ArithTool bean 无外部依赖，创建不会失败。即便失败，V2ToolGroupAdapter 和 SubagentRegistrar 都用 `getIfAvailable()` 容错，supervisor 和子 agent 都不会拿到 arith，但其他功能不受影响 |

### 回滚

7 个文件改动（1 新建 + 6 修改），`git revert` 即可。无 DB schema 变更、无配置变更、无新建依赖。

## 关联

- 设计哲学：[[arith-tool-design-philosophy]] 记忆
- E2E 验证细节：[[arith-tool-e2e-verified]] 记忆
- 子 agent 工具注册：[[subagent-meta-tool-routing]] 记忆
- 前置 ADR：[adr-code-interpreter-removal.md](adr-code-interpreter-removal.md)（code_interpreter 移除，python_exec 下沉到 analyze_data）
