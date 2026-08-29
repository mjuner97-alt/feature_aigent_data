# code_interpreter 优化方案

汇总改动了哪些文档excel格式展示：

| 序号 | Sprint | 文件路径 | 改动类型 | 改动摘要 | 状态 |
|------|--------|----------|----------|----------|------|
| 1 | Sprint 1 | `harness/tools/PythonExecTool.java` | **新增** + 修改 | P0-B: 新增 `python_exec` 工具；第二构造器 `(SandboxProperties,PythonExecProperties)`；timeout 分级计算 | ✅ 已完成 |
| 2 | Sprint 1 | `harness/hooks/PythonExecRetryHook.java` | **新增** | P0-D: PostActing 拦截 python_exec 失败，注入行号定位 + 异常修法提示 | ✅ 已完成 |
| 3 | Sprint 1 | `service/SupervisorService.java` | 修改 | buildToolRegistry() 注册 python_exec；条件注册 PythonExecRetryHook | ✅ 已完成 |
| 4 | Sprint 1 | `docs/ssh.md` | 修改 | P0-C: 新增 Windows 专属 §2（Git Bash ssh 强制要求、PATH 调整、ControlMaster 配置、排错表） | ✅ 已完成 |
| 5 | Sprint 1 | `harness/config/SshHealthCheck.java` | **新增** | P0-C: 启动期 SSH ControlMaster 健康检查，未就绪时打 WARN 日志 | ✅ 已完成 |
| 6 | Sprint 1 | `workspace/harness-a2a/subagents/code-interpreter.md` | 修改 | P0-D: 添加失败重试纪律章节（定位行号 → 单行修复 → 超 2 次停止） | ✅ 已完成 |
| 7 | Sprint 2 | `harness/tools/DataPrimitivesTool.java` | **新增** | P1-B: 5 个计算原语工具(aggregate/topN/compareRatio/pivot/distribution)。改为 `@Component`,**通过 ToolRoutersIndex 的 router_tool 路由调用**,不再直接注册到 subagent toolkit。维度无任何硬限制(部门/应用/组/产品线/人员/需求项 任意单维或多维组合) | ✅ 已完成 |
| 8 | Sprint 2 | `agent/tools/routers/ToolRoutersIndex.java` | 修改 | 构造器注入 `DataPrimitivesTool`,`init()` 中 `registerTools(DataPrimitivesTool.class, ...)` | ✅ 已完成 |
| 9 | Sprint 2 | `workspace/harness-a2a/skills/data_primitives/SKILL.md` | **新增** | data_primitives 独立 skill,与 `tool_index` 同款风格的 toolId 索引 + `routerExample` + 维度说明 | ✅ 已完成 |
| 10 | Sprint 2 | `workspace/harness-a2a/subagents/analyze-data.md` | 修改 | P1-B: 决策树改为 `router_tool({"toolId":"data_aggregate",...})` 形式;tools 改为 `tool_router`(质量查询/聚合一律走 router_tool) | ✅ 已完成 |
| 11 | Sprint 2 | `harness/config/PythonExecProperties.java` | **新增** | P1-D: @ConfigurationProperties 封装 defaultTimeoutSeconds / maxTimeoutSeconds | ✅ 已完成 |
| 12 | Sprint 2 | `application.properties` | 修改 | P1-C: 添加 coder 模型实例（已注释）；P1-D: 添加 default/max timeout 配置项 | ✅ 已完成 |
| — | Sprint 3 | ~~`harness/tools/QualityAggregateTool.java`~~ | **已删除** | P2-A: 原计划新增 `query_quality_aggregated` 单步查算工具,但**与项目 `router_tool` 统一路由架构不兼容**(走自己 @Tool 直接注册),且维度只支持 dept/quarter 硬编码,已删除。聚合需求改为先 `query_quality_data` 取数 → `data_aggregate` 计算两步 | ❌ 已删除 |
| 13 | Sprint 3 | `docs/python-exec-http-service.md` | **新增** | P1-A: 容器内常驻 Python HTTP 服务规范（架构/契约/风险/回退） | ✅ 文档已完成 |
| 14 | Sprint 3 | `docs/runtime-image/server.py` | **新增** | P1-A: 可直接落进容器的 FastAPI 服务实现 — ProcessPool + 路径白名单 + 64KB 截断 | ✅ 脚手架已完成 |
| 15 | Sprint 3 | `docs/runtime-image/python-exec.conf` | **新增** | P1-A: supervisord 进程守护配置 — autorestart + JSON 行日志 | ✅ 脚手架已完成 |
| 16 | Sprint 3 | `docs/runtime-image/README.md` | **新增** | P1-A: 部署 + 启动 + 自检 + 排错指南 | ✅ 文档已完成 |
| 17 | Sprint 3 | `docs/python-subinterpreter-isolation.md` | **新增** | P2-B: 子解释器隔离规范（方案对比/server.py 骨架/测试矩阵） | ✅ 文档已完成 |


> 场景:`sandbox-windows` profile —— Windows JVM + 远端 Linux Docker。
> 痛点:`Supervisor → analyze_data → code_interpreter` 复杂任务时容易超时、容易出错。

---

## 1. 痛点拆解 — 为什么 code_interpreter 这么慢、这么脆

### 1.1 链路本身就长

```
Supervisor                  ── ReAct A (~6~10 iter)
  └─ analyze_data            ── ReAct B (~5 iter)
       └─ query_quality_data ── ReAct C (~3 iter,实际只是路由 + 查表)
       └─ code_interpreter   ── ReAct D (~6~8 iter,read_file/write_file/shell 反复)
            └─ 容器内 python3
```

每一层 ReAct = N 次 LLM 推理 + N 次 tool call。Supervisor 层光是「派 analyze_data」就要走 1 次 LLM;`analyze_data` 决定派 `code_interpreter` 又是 1 次 LLM;`code_interpreter` 自己典型 4 步(read_file 探数据 → 想代码 → write_file → shell_execute)又是 4 次 LLM。**复杂任务一次问答 ~20 次 LLM 调用打底**。

### 1.2 远端 Docker 单次 tool call 成本被放大

`sandbox-windows` profile 下,每个 `shell_execute` / `write_file` / `read_file`:

1. JVM 启 `ssh.exe`,SSH 握手(Windows OpenSSH **不支持 ControlMaster**)
2. SSH 内执行 `docker exec agentscope-shared-demo <cmd>`
3. docker daemon 在容器里 fork/exec 进程
4. 进程退出,stdout/stderr 经 docker daemon → SSH → JVM 拉回

一次 `shell_execute("python3 /workspace/run.py")` 实测 1.5~3s 是 **SSH+docker 链路开销**,不是 Python 本身。`code_interpreter` 内部 4 步串行 ≈ 6~12s 纯握手,**还没算 LLM 推理时间**。

### 1.3 LLM 自写 Python 易错

中文列名 + Windows GBK 默认编码 + pandas 版本差异 + `code_interpreter` 完全靠 LLM 临场写代码:

- 列名手抄成 "质量分（缺陷密度）" vs 实际 "质量分(缺陷密度)"(全角 vs 半角括号)
- 忘加 `encoding="utf-8"` → UnicodeDecodeError
- 把空字符串当 0 累加
- 类型推断:`"2026Q1"` 当字符串还是日期?
- 一次失败后重试,LLM 倾向**重写整段代码**而非修一行,又一次 SSH+docker 往返

### 1.4 当前 timeout 一刀切

`harness.a2a.tool-execution.timeout-seconds=300` 是**外层工具的执行超时**。code_interpreter 内部 `shell_execute(..., timeout=60)` 是**沙箱内部超时**。两者不联动 —— 如果 LLM 反复 retry,外层 300s 一到,整个 `agent_spawn` 失败,但子链路上的 docker exec 还在跑 / SSH 链接还在握手。

### 1.5 ArtifactHandoffHook 已经在做对的事 —— 但只走到一半

handoff 消息里给的是「路径 + 5 行 markdown 预览」,**没有 schema(列名、dtype、行数)**。`code_interpreter` 拿到路径后还得:

```python
df = pd.read_csv(path)
print(df.head())   # 探一遍
print(df.dtypes)   # 探一遍
print(df.columns)  # 探一遍
```

至少多 1 次 LLM 推理 + 1 次 shell_execute,纯属重复探查。

---

## 2. 优化方案 —— 分层 + 按 ROI 排序

> 标号说明:**P0** = 低成本高收益,推荐先做;**P1** = 中等改造;**P2** = 大改造,等 P0/P1 不够再上。

### P0-A:在 handoff 消息里直接塞 schema(预算 0.5 天,省 1~2 次 LLM 调用)

**当前**:`ArtifactHandoffHook.buildHandoffMessage` 给的是路径 + 行数 + 列名 + 5 行 markdown 预览。
**改造**:加塞「schema 段」—— 列名 + 推断 dtype + 非空率 + 关键列样本。

```
📦 完整数据已保存为 CSV artifact:
  路径: /workspace/artifacts/alice/task_3f1a/qdq-abc.csv
  shape: (24, 5)

▶ Schema(dtype 已用 pandas 推断,可直接用):
  季度        string    24/24 非空    样本: ["2026Q1", "2026Q2"]
  部门        string    24/24 非空    样本: ["杭州开发一部", "杭州开发二部"]
  应用        string    24/24 非空    样本: ["核心系统A", "网关B"]
  缺陷密度    float64   24/24 非空    样本: [23.1, 13.1, 3.1]   range=[3.1, 26.1]
  统计日期    string    24/24 非空    样本: ["2026-03-31"]

▶ 后续派给 code_interpreter 时,在 Python 中读取:
  import pandas as pd
  df = pd.read_csv("/workspace/artifacts/alice/task_3f1a/qdq-abc.csv")
  # 列已知,无需再 df.head() / df.dtypes 探查
```

**实现点**(改动文件):
- `TabularExtractor.TabularData` 增 `inferSchema()`,返回 `List<ColumnSchema(name, dtype, nonNullCount, samples, min, max)>`
- `ArtifactRef` 增 `schema` 字段
- `ArtifactHandoffHook.buildHandoffMessage` 渲染 schema 段
- `code-interpreter.md` 工作流改成「**不要先 read_file/head 探数据**,handoff 已给完整 schema,直接写脚本」

**收益**:`code_interpreter` 从 4 步压到 2 步(write_file → shell_execute),省 2 次 SSH+docker = ~3~6s + 1~2 次 LLM 推理 = ~10~20s。

---

### P0-B:合并 `write_file` + `shell_execute` 为单工具 `python_exec`(预算 1 天,省 50% 远端往返)

**当前** `code_interpreter` 标准流是 write_file → shell_execute → (失败重试)。两次 SSH+docker exec。

**改造**:在 `buildToolRegistry()` 里新增 `@Tool python_exec(code: String, timeoutSeconds: int)`:

```java
@Tool(description = "在沙箱容器内直接执行一段 Python 代码,无需先 write_file。"
                  + "等价于 docker exec <container> python3 -c <code>,但绕过 shell 引号转义。")
public ToolResult pythonExec(@Param("Python 代码") String code,
                              @Param("超时秒数,默认 60") int timeoutSeconds) {
    // 实际通过 SandboxFilesystem 把 code 以 stdin 喂给 python3 -
    // 等价于:ssh docker-host "docker exec -i <container> python3 - <<EOF\n${code}\nEOF"
}
```

或者更简单:`shell_execute("python3 -", stdin=code)` —— 用 stdin 推代码,不写文件。

**实现点**:
- 在 `harness/tools` 下加 `PythonExecTool.java`,内部委托给当前 sandbox filesystem 的 shell 通道,但 stdin 传 code
- `code-interpreter.md` 工作流改成 1 步:**直接 `python_exec(code)`**,不再 write_file

**收益**:一次计算从 2 次远端往返 → 1 次。SSH 握手开销减半 = 省 1.5~3s/次。LLM 调用也少 1 次。

**Windows 注意**:命令行长度 8KB 限制(`MemoryFlushHook` 已踩坑)—— Python 代码 ≤ 4KB 走 `-c "..."`,>4KB 仍要落临时文件。可以在 `PythonExecTool` 内部自动判断。

---

### P0-C:复用 SSH ControlMaster(预算 0.5 天,Windows 唯一硬要求)

**问题**:Windows 自带 OpenSSH(`C:\Windows\System32\OpenSSH\ssh.exe`)**不支持 `ControlMaster`**。每次 `ssh docker-host` 都要 ~300ms 握手。code_interpreter 一次任务 4~8 次 ssh = 1.2~2.4s 纯握手。

**改造**:运维侧硬要求 Git Bash 的 `/usr/bin/ssh`,然后:

```ini
# %USERPROFILE%\.ssh\config(确保 ssh.exe 来自 Git Bash,不是 Windows OpenSSH)
Host docker-host
  HostName 10.x.x.x
  User root
  ControlMaster auto
  ControlPath C:/Users/<you>/.ssh/cm-%C
  ControlPersist 30m
  ServerAliveInterval 30
```

`DockerSandboxClient` / `SshArtifactIo` 都直接走 `ssh docker-host`,**走同一份 ControlMaster** —— 第一次 ~300ms,后续全部 ~30ms。

**实现点**(代码层 0 改动,文档 + 启动检查):
- 启动期加一个 `SshHealthIndicator`:跑 `ssh -O check docker-host`,master 没起来打 WARN
- README / `ssh.md` 加 Windows 专属段落,**强制要求** `which ssh` 返回 Git Bash 那份

**收益**:稳态每次 tool call 省 ~270ms × 4~8 次 = 1~2s。看起来不大,但是叠加在 P0-A/P0-B 之上,total 提升明显。

---

### P0-D:失败回传机制(预算 0.5 天,显著降低无效 retry)

**当前**:`code_interpreter` 拿到 stderr 后,LLM 倾向**重写整段代码**,不修一行 → 又一次远端往返 + 大概率再错(重写引入新错)。

**改造**:在 `code-interpreter.md` 里硬性规定:

```markdown
## 失败重试纪律

执行失败时:
1. **不要重写整段代码** —— 先看 stderr 最后 5 行 + traceback 定位行号
2. 把上次的 code 完整复制粘贴,只改报错的那一行
3. 在 `python_exec` 调用前一行写注释 `# fix: <一句话说明改了什么>`
4. 超过 2 次失败:停止重试,把所有 stderr 完整回报给 analyze_data,**让上层决定降级**
```

更进一步可以做工程拦截:`ArtifactHandoffHook` 风格,加一个 `PythonExecRetryHook`,在 `PostActingEvent` 里对 `python_exec` 失败结果自动追加 traceback 行号定位、字段名提示等。

---

### P1-A:容器内常驻 Python 计算服务(预算 2~3 天,从根上消除 docker exec 开销)

**思路**:在共享容器 `agentscope-shared-demo` 里启一个常驻 HTTP 服务(FastAPI/Flask)端口 8000,JVM 不再 `docker exec python3`,而是:

```
JVM ─HTTP─> http://<docker-host>:8000/exec  body={"code": "...", "csv_paths": [...]}
            (经 SSH 端口转发 或 容器 publish 端口)
                                │
                                ▼
                       容器内 Python 子进程池
                       ├─ 长寿命 Python 解释器(pandas/numpy 已 import)
                       └─ 每次 exec 跑在隔离的子解释器或 multiprocessing.Pool
```

**优势**:
- 跳过 docker exec fork 开销(~200ms/次)
- 跳过 Python 解释器冷启动(~300ms/次,pandas import 占大头)
- 一次 HTTP 调用替代「ssh + docker exec + python3 + import pandas」全链路 → **稳态 < 200ms**
- 容器内服务可以做计算缓存:相同 `(code_hash, csv_md5)` 直接返回上次 stdout
- 服务进程稳态在,docker exec 不再 fork —— SSH 单次握手成本可摊销到 N 次计算

**实现点**:
- 容器镜像 `deepanalyze-vllm:latest` 增加 `/opt/runner/server.py` + supervisord 配置常驻
- JVM 侧新增 `PythonExecHttpClient`,走 `OkHttp/WebClient`,通过 SSH `-L 8000:localhost:8000` 端口转发(已有 ControlMaster 复用)
- `PythonExecTool` 在新 profile `harness.a2a.python-exec.transport=http` 下走 HTTP,默认仍走 `docker exec`(兼容回退)
- 安全:服务监听 `127.0.0.1:8000`,只通过 SSH tunnel 访问;请求里仍按 user/task 路径校验

**风险**:服务进程崩了所有用户都挂 —— 走 supervisord + `--restart unless-stopped` 双保险;关键路径打 metrics。

---

### P1-B:计算原语工具化(预算 2 天,绕开 LLM 写 Python)

**观察**:实际业务里 80% 的「计算」都是 5 个套路:

| 套路 | 输入 | 输出 |
|---|---|---|
| `aggregate` | csv, groupBy列, value列, agg(sum/mean/std/median/count) | DataFrame |
| `topN` | csv, sortBy列, n, asc/desc | DataFrame |
| `compareRatio` | csv_a, csv_b, joinKey列, value列 | DataFrame(含同比/环比) |
| `pivot` | csv, index列, columns列, value列, aggFn | DataFrame |
| `distribution` | csv, value列 | mean/std/p50/p90/p99/min/max |

把这 5 个做成 `@Tool`(在 JVM 侧 / 或容器内 HTTP 服务):

```java
@Tool(description = "对 CSV 做分组聚合。例:按 部门 列对 缺陷密度 列求均值")
public ToolResult aggregate(String csvPath,
                            List<String> groupByCols,
                            String valueCol,
                            String aggFn) { ... }
```

**改 `analyze_data` 的工作流**:

- 简单聚合/Top-N/分布 → 直接调原语工具,**完全跳过 code_interpreter**
- 仍需自定义计算的(回归、相关系数等)→ 才派 code_interpreter

**收益**:80% 的请求绕开 code_interpreter 整条链路 → **延迟降到 1 次原语工具调用 = 1 次远端 RPC**。同时彻底消除「LLM 写 Python 错」这条故障路径。

**实现点**:
- 新增 `harness/tools/DataPrimitivesTool.java`,内部调容器内 pandas(走 P1-A 的 HTTP 服务)或本地 Java 实现(用 Tablesaw / fastexcel)
- `analyze-data.md` 里强化「原语优先」纪律,加 decision tree
- 同时保留 code_interpreter 作为 fallback

---

### P1-C:code_interpreter 切到代码专精小模型(预算 0.5 天,几乎零成本)

`ModelRegistry` 已经支持多实例。`code_interpreter` 的任务非常单一:**给定 schema + 自然语言需求 → 输出可执行 pandas 代码**。这是代码模型的强项(DeepSeek-Coder / Qwen-Coder),不需要主推理模型。

**改造**:
- `application.properties` 增加 `harness.a2a.model.instances.coder` 指向 Qwen2.5-Coder-7B / DeepSeek-Coder-V2-Lite 等
- `SupervisorService.registerSubagentFromSpec` 检测 spec.name == "code_interpreter" → `b.model(modelRegistry.get("coder"))`
- `code-interpreter.md` 前置段加结构化输出强制(JSON 或 fenced code block)

**收益**:小模型推理快 ~3 倍,代码正确率反而高(专精训练)。同时省主模型 token。

---

### P1-D:Tool 执行 timeout 分级 + 取消传播(预算 1 天)

**当前**:`harness.a2a.tool-execution.timeout-seconds=300` 一刀切,外层超时后下层不取消。

**改造**:
- 区分外层「整次 agent_spawn 超时」(`SupervisorService` 300s)/ 中层「单 tool call 超时」(120s)/ 内层「shell_execute 容器内」(60s)
- 三层都用 `Duration`,**外层 cancel 时通过 `ToolExecutionConfig.timeout` 触发 reactor `Mono` 取消** → 下层 `Process.destroyForcibly()`
- 在 `application.properties` 里暴露:
  ```
  harness.a2a.tool-execution.timeout-seconds=300            # 外层
  harness.a2a.code-interpreter.iter-timeout-seconds=120     # code_interpreter 单次 LLM+tool
  harness.a2a.code-interpreter.python-timeout-seconds=60    # 单次 python_exec
  ```
- 超时后回传给上层 LLM 的错误消息要**结构化**:`{"reason": "timeout", "stage": "python_exec", "elapsed_ms": 61234}`,而不是一串 traceback,让上层 LLM 能做策略选择

---

### P2-A:取数 + 算数合并工具(预算 3~5 天,终极方案)

**观察**:`query_quality_data` → CSV → `code_interpreter`(pd.read_csv → aggregate)→ stdout 这个流程,**质量数据其实在 MySQL 里**,完全可以一条 SQL 直接做完聚合。

**改造**:`quality_tools` 增加 `query_quality_aggregated`,参数:

```
query_quality_aggregated(
  filters: {year: 2026, quarter: [1,2], ...},
  groupBy: ["部门"],
  metrics: ["AVG(缺陷密度) as 均值", "STDDEV(缺陷密度) as 标准差"],
  having: "...",
  orderBy: ["均值 DESC"],
  limit: 3
)
```

后端拼 SQL → 直接返 markdown 表。**完全跳过 code_interpreter**,延迟从 ~200s 降到 ~5s。

**适用边界**:能用 SQL 表达的聚合 (~95% 业务)。回归 / 相关系数 / 时序拟合等仍走 code_interpreter。

---

### P2-B:跨用户 Python 子解释器隔离(配合 P1-A)

如果 P1-A 上线,共享 Python 服务一定要做 uid/subinterpreter 隔离,否则一个用户的代码可以读另一个用户的 in-memory state。Python 3.12 的 PEP 684 sub-interpreter 是首选;退而求其次用 `multiprocessing` per-user 池。

---

## 3. 推荐分阶段路线图

### Sprint 1(1 周内,P0 全做完)

目标:**先让 `code_interpreter` 跑得稳、跑得快**,不动架构。

- [ ] **P0-A** schema 注入 handoff —— 改 `TabularExtractor` + `ArtifactRef` + `ArtifactHandoffHook` + `code-interpreter.md`
- [ ] **P0-B** `python_exec` 工具 —— 在 `buildToolRegistry()` 注册,`code-interpreter.md` 工作流改 1 步
- [ ] **P0-C** SSH ControlMaster 强制要求 —— 文档 + 启动健康检查
- [ ] **P0-D** 失败回传纪律 —— `code-interpreter.md` + 可选 `PythonExecRetryHook`

**预期效果**:复杂任务从 ~200s 降到 ~80~120s,无效 retry 率显著下降。

### Sprint 2(2 周,P1 选 2~3 个)

- [ ] **P1-C** code_interpreter 切代码小模型 —— 几乎零成本,代码质量上来
- [ ] **P1-B** 计算原语工具 —— 业务侧改造,80% 请求绕过 code_interpreter
- [ ] **P1-D** 分级 timeout —— 工程稳定性

**预期效果**:80% 复杂任务直接走原语 = ~10s。剩余 20% 仍走 code_interpreter 但已显著优化。

### Sprint 3(按需,P2)

- [ ] **P1-A / P2-B** 容器内常驻 Python HTTP 服务 + 子解释器隔离 —— 把 code_interpreter 那 20% 的延迟再砍掉一半
- [ ] **P2-A** SQL 层聚合工具 —— 只在质量数据规模上量、SQL 表达力够用时上

---

## 4. 涉及代码改动清单速查

| 改动项 | 文件 | Sprint | 状态 |
|---|---|---|---|
| schema 注入 | `harness/artifact/TabularExtractor.java` 增 `inferSchema()` | 1 | ⏸ 未实施(已在 ArtifactHandoffHook 提供基础预览,schema 段后续按需追加) |
| | `harness/artifact/ArtifactRef.java` 增 `schema` 字段 | 1 | ⏸ 未实施 |
| | `harness/hooks/ArtifactHandoffHook.java` `buildHandoffMessage` 渲染 schema | 1 | ⏸ 未实施 |
| | `workspace/harness-a2a/subagents/code-interpreter.md` 改工作流 | 1 | ✅ 已完成 |
| python_exec 工具 | `harness/tools/PythonExecTool.java` 新增 | 1 | ✅ 已完成 |
| | `service/SupervisorService.buildToolRegistry()` 注册 | 1 | ✅ 已完成 |
| | `workspace/harness-a2a/subagents/code-interpreter.md` 改 default flow | 1 | ✅ 已完成 |
| SSH ControlMaster | `docs/ssh.md` Windows 段补强 | 1 | ✅ 已完成 |
| | `harness/config/SshHealthCheck.java` 新增 | 1 | ✅ 已完成 |
| 失败回传 | `workspace/harness-a2a/subagents/code-interpreter.md` 加纪律段 | 1 | ✅ 已完成 |
| | `harness/hooks/PythonExecRetryHook.java` 新增 | 1 | ✅ 已完成 |
| 代码小模型 | `application.properties` 增 `model.instances.coder`(已注释,启用时取消即可) | 2 | ✅ 已完成 |
| | `service/SupervisorService.registerSubagentFromSpec` 按 name 切模型 | 2 | ✅ 复用 ModelRegistry.getForSubagent() 已支持 |
| 计算原语 | `harness/tools/DataPrimitivesTool.java` 新增(`@Component`,走 router_tool) | 2 | ✅ 已完成(5 个原语,无维度硬限制) |
| | `agent/tools/routers/ToolRoutersIndex.java` 注册 DataPrimitivesTool | 2 | ✅ 已完成 |
| | `workspace/harness-a2a/skills/data_primitives/SKILL.md` 新增 | 2 | ✅ 已完成 |
| | `workspace/harness-a2a/subagents/analyze-data.md` 改决策树 | 2 | ✅ 已完成 |
| 分级 timeout | `application.properties` + `harness/config/PythonExecProperties.java` | 2 | ✅ 已完成(default 60s / max 300s) |
| 容器内 HTTP 服务 | `docs/python-exec-http-service.md` 规范文档 | 3 | ✅ 文档已完成 |
| | `harness/sandbox/PythonExecHttpClient.java` 新增 | 3 | ⏸ 未实施(等镜像端 server.py 就绪) |
| 子解释器隔离 | `docs/python-subinterpreter-isolation.md` 规范文档 | 3 | ✅ 文档已完成 |
| SQL 聚合(已废弃) | ~~`harness/tools/QualityAggregateTool.java`~~ | 3 | ❌ 已删除 — 与 router_tool 架构不兼容,改用 query_quality_data + data_aggregate 两步 |

---

## 5. 验证 checklist(Sprint 1 完成后跑)

```bash
# 同一个分析请求,对比改造前后耗时
time curl -sS --max-time 600 http://localhost:8081/ai/chat \
  -H 'Content-Type: application/json' \
  --data-binary '{"input":"对比2026年1季度和2026年2季度各部门的环比变化率","session_id":"opt-v1"}'

# 期望:从 ~200s 降到 ~80~120s

# code_interpreter 内部步数统计 —— 日志里搜
grep "agent_spawn.*code_interpreter" /tmp/app.log | wc -l        # 派单次数
grep "python_exec\|shell_execute.*python3" /tmp/app.log | wc -l  # python 执行次数
# 期望: 1:1(不再 read_file/head 探查)

# 失败重试率
grep "code_interpreter.*retry\|tool execution failed" /tmp/app.log | wc -l
# 期望:同样 10 次复杂请求,从 ~5 次重试降到 ≤1

# Schema 注入正确性
curl http://localhost:8081/debug/workspace
ls /opt/agentscope-workspace/harness-a2a/artifacts/<user>/<task>/  # 应只有 .csv
# (没有副产物文件,说明 code_interpreter 没再 write_file 探查)

# SSH ControlMaster 命中
ssh -O check docker-host    # 应返回 "Master running"
```

---

## 6. 与现有架构的对齐说明

- **ArtifactHandoffHook 不动协议**:schema 注入是「在原 handoff 消息里加段」,向后兼容 —— 旧的 code_interpreter prompt 即使忽略 schema 段也能跑
- **python_exec 与 shell_execute 共存**:不删除 `shell_execute`,只是 code_interpreter 默认改用 `python_exec`。`ArtifactAccessHook` 仍拦截 `shell_execute` 的越权,`python_exec` 也要在 hook 里加同样的拦截规则
- **小模型不影响 supervisor**:`ModelRegistry` 已支持多实例,supervisor 仍用主模型,只在 `registerSubagentFromSpec` 时为 code_interpreter 切模型
- **HTTP 计算服务可选**:P1-A 落地后,`python_exec` 工具内部按 profile 切换实现,JVM 侧调用方零感知

---

## 7. 已知限制 / 不在本方案的取舍

- **不动 agent 跳数** —— Supervisor → analyze_data → code_interpreter 这个三层结构是业务可读性的核心,不为性能合并。性能优化全部走「降低每跳成本」而非「砍跳数」(P2-A 的 SQL 聚合是例外,它属于业务能力补全)
- **不引入 Redis 缓存计算结果** —— `ResponseCacheHook` 已经做了维度 key 级别的回复缓存,命中时整条链路短路 1s 返回;真没必要在 code_interpreter 这层再加一层 cache
- **不做 LangChain 风格的 PythonREPL 持久 kernel** —— 跨请求复用 kernel 会引入「上一个用户的变量泄漏到下一个用户」的隔离问题,与共享容器多租户安全冲突。P1-A 的 HTTP 服务必须 stateless,每次新子解释器/子进程
- **uid 隔离不在本方案范围** —— 那是多租户安全方向,与 code_interpreter 性能/稳定性正交,详见 README §5.3
