# ADR: 移除 code_interpreter 子智能体，python_exec 下沉到 analyze_data

> Date: 2026-07-18
> Status: Accepted
> Context: v2 Stage 1-8 完成后的架构简化

## Decision

**取消 `code_interpreter` 独立子智能体；`python_exec` 工具直接由 `analyze_data` 子智能体持有。`QualitySupervisor` 已通过 `V2ToolGroupAdapter` 持有 `python_exec`，无需重复下发。`query_data` 不持有 `python_exec`，保持纯查询委员角色。**

## Rationale

### code_interpreter 独立成子智能体的必要性消失

原架构里 `code_interpreter` 是 4 个子智能体之一，专门跑 `python_exec`。但实际看下来它的存在价值站不住：

1. **supervisor 本来就有 `python_exec`** -- `HarnessA2aRunnerV2` 第 305-311 行通过 `V2ToolGroupAdapter` 给主 agent 装了 `quality_tools + data_primitives + python_exec` 三个工具组（`V2ToolConfig.java:143-163`）。沙箱开着时 supervisor 现在就能直接调 `python_exec`，从未真正"缺这个工具"。

2. **analyze_data 80% 的计算根本不走 code_interpreter** -- `analyze_data.md` 决策树（第 31-65 行）首选 `data_primitives`（`data_aggregate` / `data_distribution` / `data_top_n` / `data_compare_ratio` / `data_pivot`）。这些是 Java 端按模板拼 Python 的工具，LLM 不写 Python 代码、一次远端往返就完事。**只有决策树最后一条**（回归 / 相关系数 / 时序拟合 / 复杂自定义）才 `agent_spawn(code_interpreter)`，每次多 1 整层 ReAct ~6 次 LLM 调用 + 1 次 agent_spawn 远端往返。

3. **query_data 不该有 python_exec** -- 它是纯查询委员（`tools: tool_router`），加了反而让 LLM 在该查还是该算上摇摆。

### Java 侧零改动可行

`SubagentRegistrar.java:205-214` 的 hook 接线已经是"声明了 python_exec 就自动挂"模式：

```java
boolean hasPythonExec = toolNames.contains("python_exec");
if (artifactHandoffHook != null) sub.hook(artifactHandoffHook);
if (artifactAccessMiddleware != null) sub.middlewares(List.of(artifactAccessMiddleware));
if (hasPythonExec && pythonExecRetryHook != null) sub.hook(pythonExecRetryHook);
```

只需改 `analyze_data.md` 的 `tools:` 列表从 `tool_router` 改成 `[tool_router, python_exec]`，`PythonExecRetryHook` 自动挂上。`ArtifactHandoffHook` / `ArtifactAccessMiddleware` 对所有子 agent 都挂，无需条件判断。

`toolRegistry`（`SubagentRegistrar.java:99-102`）已包含 `python_exec` 逻辑名（沙箱 profile 开启时 `PythonExecTool` bean 创建，参见 `V2ToolConfig.java:181-186` 的 `@ConditionalOnProperty`），fail-fast 校验（`SubagentRegistrar.java:119-128`）不会触发。

`dev` 和默认 profile 的 `sandbox.enabled=true`（`application.properties:198` + `application-dev.properties:83`），所有 sandbox-* profile 同样为 true。无 profile 风险。

## Changes

### 删除

- `src/main/resources/workspace/agent-subagents/code_interpreter.md` -- 整个 spec 文件移除。`SubagentRegistrar` 构造函数第 117 行 `AgentSpecLoader.loadFromDirectory` 扫目录，文件没了就不加载，无需改 Java。

### 修改

| 文件 | 改动 |
|---|---|
| `src/main/resources/workspace/agent-subagents/analyze_data.md` | front matter `tools: tool_router` -> `tools: [tool_router, python_exec]`；决策树最后一条 `agent_spawn(code_interpreter)` -> `python_exec(code=..., timeoutSeconds=180)`；新增「调 python_exec 的硬规则」+「失败重试纪律」+「数据传递约定」三段；清理「为什么这么做」段对 code_interpreter 的引用 |
| `src/main/resources/workspace/AGENTS.md` | 删「4. code_interpreter」段；路由决策树删 Python 分支，`generate_skill` 改 `└─`；新增"所有 Python 计算一律派 analyze_data"注；「数值计算硬规则」段 3 处 code_interpreter -> analyze_data；删「code_interpreter 不会自己查质量数据」行 |
| `src/main/java/com/agentscopea2a/v2/hooks/ArtifactHandoffHook.java` | 第 157/161/162 行 user-facing handoff 文案改中性（去掉 code_interpreter 字样），handoff 协议本身（CSV 路径 + Schema + 预览）不变 |
| `src/main/resources/workspace/skills/data_primitives/SKILL.md` | description 去掉"绕过 code_interpreter"；末尾"派 code_interpreter 子 agent" -> "改用 python_exec 直接写 Python" |

### 未改（按计划保留）

- `PythonExecTool.java` / `PythonExecRetryHook.java` / `DataPrimitivesTool.java` / `TabularExtractor.java` / `ArtifactRef.java` / `ArtifactAccessMiddleware.java` / `SubagentRegistrar.java` / `SshHealthCheck.java` / `V2SandboxConfig.java` 等 Java 文件 Javadoc 里的 `code_interpreter` 字样属历史背景说明，不影响运行，保留。
- `docs/rc2/*` 一堆 markdown 里的 `code_interpreter` 字样属历史文档，保留。

## Consequences

### 正面

- **复杂计算少一跳**：回归 / 相关系数 / 拟合类请求不再 `agent_spawn(code_interpreter)`，省 1 整层 ReAct ~6 次 LLM 调用 + 1 次 agent_spawn 远端往返。
- **心智模型更简单**：supervisor 路由决策树从 4 个子智能体收缩到 3 个，"什么时候派 code_interpreter" 这个判断消失。
- **失败重试链路统一**：`PythonExecRetryHook` 之前在主 agent 和 code_interpreter 子 agent 上都挂，现在只在主 agent 和 analyze_data 子 agent 上挂，逻辑一致。
- **Java 侧零改动**：所有 hook/middleware 接线已存在，spec 改 `tools:` 列表即生效。

### 负面 / 风险

- **LLM 短期可能仍调 `agent_spawn(code_interpreter)`**：刚切 prompt 时 LLM 训练数据里的旧模式可能冒出来。`agent_spawn` 工具会回报 "unknown subagent code_interpreter"，LLM 看到 error 后会自纠。若高频出错，可在 `analyze_data.md` 失败模式段显式补一句"不要调 agent_spawn(code_interpreter)，它已不存在"。
- **analyze_data 角色变重**：之前 analyze_data 只负责"决策 + 调 data_primitives"，复杂计算外包给 code_interpreter。现在 analyze_data 自己写 Python 代码，对 LLM 的 Python 编码能力要求提高。`PythonExecRetryHook` 的失败提示（`✦ 失败行` / `✦ 异常类别` / `✦ 常见修法`）是这条路径的兜底。
- **code_interpreter.md 删除后无回滚锚点**：如需恢复，需从 git history 重建 spec 文件。建议保留 git 历史不 squash。

### 非影响

- `data_primitives` 5 个工具（`data_aggregate` / `data_distribution` / `data_top_n` / `data_compare_ratio` / `data_pivot`）仍是 analyze_data 决策树的首选路径，80% 的计算请求仍走 Java 模板拼代码，不写 python_exec。本次只是把"决策树最后一条"从 spawn code_interpreter 改成直接调 python_exec。
- `ArtifactHandoffHook` 的 CSV 落盘 + Schema 推断 + 预览生成逻辑不变，handoff 消息仍正常产出，只是文案改了"code_interpreter"字样。
- 沙箱共享单容器 `agentscope-shared-demo` 不变，python_exec 仍走 `ssh <target> docker exec -i <container> python3 -` 路径。

## Verification

### 编译

```bash
cd analysis-project
mvn compile
```
BUILD SUCCESS。

### 启动

启动日志应见：
- `SubagentRegistrar: loaded 3 subagent specs`（不再是 4）
- `SubagentRegistrar: toolRegistry built with 3 entries: [tool_router, python_exec, skill_save]`
- 无 `code_interpreter` 字样

### E2E

| 用例 | 预期路径 |
|---|---|
| "分析 2026 Q1 各部门质量分分布" | supervisor -> `agent_spawn(analyze_data)` -> `router_tool(data_distribution)` -> 返回统计。**不调 python_exec，不 spawn code_interpreter** |
| "拟合 2026 Q1 各部门质量分与缺陷数的线性回归" | supervisor -> `agent_spawn(analyze_data)` -> `router_tool(quality_query_by_department_quarter)` 拿 CSV -> `python_exec(code="...pd.read_csv...np.polyfit...")` -> 返回回归系数。**不再有 agent_spawn(code_interpreter)** |
| 任何带 markdown 表格的工具返回 | handoff 消息含 `▶ 后续 Python 计算时,在 Python 中读取:`，**不含** `code_interpreter` 字样 |
| 故意写错列名触发 python_exec 失败 | 日志见 `PythonExecRetryHook annotated failure (exit=...)`，工具结果末尾有 `✦ 失败行` / `✦ 异常类别: KeyError` / `✦ 常见修法:` 段 |

## References

- 计划文件：`C:\Users\Windows\.claude\plans\harmonic-booping-hennessy.md`
- `SubagentRegistrar.java` -- 子智能体注册 + hook 自动接线（第 99-102、119-128、205-214 行）
- `V2ToolConfig.java` -- `PythonExecTool` bean 条件装配（第 181-186 行）+ `V2ToolGroupAdapter` 给主 agent 装 python_exec（第 143-163 行）
- `HarnessA2aRunnerV2.java` -- 主 agent hook 链 + toolkit 接线（第 235-311 行）
- `PythonExecRetryHook.java` -- 失败重试提示 hook
- `ArtifactHandoffHook.java` -- CSV 落盘 + handoff 消息（第 142-164 行 buildHandoffMessage）
- `PythonExecTool.java` -- python_exec 工具实现（沙箱 / 远程 docker / 宿主三种 transport）
