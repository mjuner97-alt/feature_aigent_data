# 子智能体之间大数据载荷的传递方案

> **场景**:`analyze_data` 子智能体派 `query_quality_data` 拿到 24 行 × N 列的质量数据表,需要再派 `code_interpreter` 用 pandas 算均值/方差/相关系数。当前做法是把整张表**塞进** `agent_spawn(task=...)` 的字符串里 —— 几百行数据就把 LLM 的 prompt 撑得很大,token 烧得厉害,而且 LLM 在"复制粘贴"过程里很容易把数字抄错或截断。
>
> **目标**:让 `query_quality_data` 的结果变成一个"变量"(artifact),`code_interpreter` 在沙箱里**一句 `pd.read_csv(handle)`** 就能拿到完整 DataFrame,中间链路只传一个短引用,不再让 LLM 当中转。

---

## 1. harness 现成有什么 — 摸底

把相关源码看了一圈,harness 模块本身**没有一个开箱即用的"工具结果 → 命名变量 → 跨 agent 注入到 Python 命名空间"机制**。但已经有 3 块拼图可以拼出这个能力:

### 1.1 `ToolResultEvictionHook` —— 自动落盘 + 占位符

- 源码:[agentscope-harness/.../hook/ToolResultEvictionHook.java](../../../../../agentscope-harness/src/main/java/io/agentscope/harness/agent/hook/ToolResultEvictionHook.java)
- 行为:工具结果超过 `maxResultChars`(默认 80000)时,**写到 `AbstractFilesystem`** 的 `/large_tool_results/<agent>/<toolCallId>` 路径(默认配置见 [`ToolResultEvictionConfig.DEFAULT_EVICTION_PATH`](../../../../../agentscope-harness/src/main/java/io/agentscope/harness/agent/memory/compaction/ToolResultEvictionConfig.java#L52)),在上下文里把工具结果替换成:
  ```
  Tool output was too large (12,345 chars) and has been saved to `/large_tool_results/analyze_data/abc123`.
  To read the full output, use `read_file` with path `/large_tool_results/analyze_data/abc123`.

  Preview (first 500 chars): ...
  ```
- **能解决一半**:同一个 agent 自己后续可以 `read_file` 取回。
- **不够的地方**:
  - 触发阈值是 80000 字符,质量数据表通常达不到 → 默认不会触发。
  - 占位符里给的路径要求**接收方也能 `read_file` 同一个路径** —— 在我们这个场景里,这等价于"`code_interpreter` 必须和写文件的 `analyze_data` 共享同一个 `AbstractFilesystem`"。
  - 内容是原始字符串(那张 markdown 表),不是机器可读格式。`code_interpreter` 拿到后还要用 Python 解析,容易出错。

### 1.2 共享沙箱文件系统 + `IsolationScope`

- 源码:[SandboxFilesystemSpec](../../../../../agentscope-harness/src/main/java/io/agentscope/harness/agent/filesystem/spec/SandboxFilesystemSpec.java) / [IsolationScope](../../../../../agentscope-harness/src/main/java/io/agentscope/harness/agent/IsolationScope.java) / [AbstractSandboxFilesystem](../../../../../agentscope-harness/src/main/java/io/agentscope/harness/agent/filesystem/sandbox/AbstractSandboxFilesystem.java)
- 行为:supervisor 和子智能体如果用**同一份 `SandboxFilesystemSpec` + 同一个 `IsolationScope`**(默认 `SESSION`),**会被路由到同一个 Docker 容器,共享同一个 `/workspace`**。
- 在 [SupervisorService.registerSubagentFromSpec()](../src/main/java/io/agentscope/examples/harness/a2a/service/SupervisorService.java#L264-L271) 里已经做了:`if (sandboxFilesystem != null) sub.filesystem(sandboxFilesystem);` —— supervisor 启了 sandbox 时,子智能体共享同一个 spec,**写在 `/workspace` 下的文件可以被任何子智能体读到**。
- 推论:**只要走 sandbox profile,通过 `/workspace/<path>` 在子智能体之间传文件就是天生支持的**。
- **不够的地方**:`QualityTools` 是个普通 Java `@Tool`,在 JVM 里跑,**没有 `AbstractFilesystem` 注入** —— 它没法直接 `write` 到沙箱容器里。

### 1.3 `agent_spawn` 的 `task` 是字符串

- 源码:[AgentSpawnTool.java](../../../../../agentscope-harness/src/main/java/io/agentscope/harness/agent/tool/AgentSpawnTool.java)
- 参数只有 `agent_id` / `task` / `label` / `timeout_seconds` —— **没有 `artifacts` / `attachments` 之类的结构化负载位**。
- 推论:跨 agent 传递只能借助**双方都能访问的存储**(文件系统、KV、DB),`task` 里只塞**短引用**(路径 / id / 变量名)。

### 结论(harness 现状)

**没有"返回值变量 / Python 命名空间注入"这种语言级机制**,但**有"沙箱共享 FS + LLM 路径引用"这种 OS 级机制**。这个场景的最优解 = 拿 1.1 / 1.2 / 1.3 拼出一个**显式的 Artifact 协议**,而不是寄希望于 evictionHook 默认触发。

---

## 2. 推荐方案 —— Artifact Handoff Protocol(显式 + 渐进)

按"改动量从小到大、覆盖能力从弱到强"分 3 档。**强烈推荐 P1**,P0 是兜底快速止血,P2 是真正生产级的彻底方案。

### 2.1 P0(零代码,1 分钟) —— 把 eviction 阈值压到比 query 结果还小

`application.yml` 加:

```yaml
harness:
  a2a:
    tool-eviction:
      max-chars: 2000        # 默认 80000 → 改 2000,query_quality_data 一张表稳超
```

[SupervisorService.java:94](../src/main/java/io/agentscope/examples/harness/a2a/service/SupervisorService.java#L94) 已经读了这个开关。改完后:

1. `analyze_data` 派 `query_quality_data`,结果原样存到 `/large_tool_results/query_quality_data/<id>`(在 supervisor / `analyze_data` 各自的 `AbstractFilesystem` 里)。
2. `analyze_data` 拿到的工具结果是占位符 +500 字预览 +路径。
3. `analyze_data` 派 `code_interpreter` 时,**把占位符里的路径原封不动塞进 task 字符串**,例如:`"读取 /large_tool_results/query_quality_data/abc123,把表格转 DataFrame,算每列 mean/std/Top-3"`。
4. `code_interpreter` 在 sandbox 里 `read_file('/large_tool_results/query_quality_data/abc123')` 拿原文,再用 Python 解析 markdown 表。

**优点**:零编码、零业务改动。
**缺点**:
- 只在 **sandbox profile** 下生效(三个 agent 共享 `/workspace`);默认 profile 下各 agent 的 filesystem 是各自的本地工作目录,跨进程不一定能读。
- 拿到的还是 markdown 表,`code_interpreter` 还要写 Python 解析(脆,空格/中文分隔符容易错位)。
- 占位符路径是 LLM 自己抄的,有几率抄错。

### 2.2 P1(推荐,约 80 行代码) —— `QualityTools` 输出 CSV artifact + 引用回显

这是我推荐的核心方案。**让查询工具自己负责把数据写成机器可读 artifact,工具返回值里只带短引用**,LLM 全程不参与数据搬运。

#### 2.2.1 协议设计

每次 `QualityTools` 的查询都同时干两件事:

1. **artifact**:把结果写到 `<workspace>/artifacts/<artifact-id>.csv`(host 写 + sandbox 通过 bind mount 看见,或纯 sandbox 写)。CSV 是机器可读,pandas 一句话搞定。
2. **inline 返回值**:返回**短摘要 + artifact 引用**给 LLM 看,例如:

   ```
   query_quality_data 查询完成
   - 行数: 24, 列: 季度, 部门, 应用, 质量分
   - 数据文件: /workspace/artifacts/qd-2026q1-hzdev1-7a2f.csv
   - 前 5 行预览:
     | 季度 | 部门 | 应用 | 质量分 |
     | 2026年1季度 | 杭州开发一部 | F-CMS | 23.1 |
     ...

   ▶ 在 code_interpreter 中读取:
     df = pd.read_csv("/workspace/artifacts/qd-2026q1-hzdev1-7a2f.csv")
   ```

   `analyze_data` 看到这种结构化提示后,派 `code_interpreter` 时只要把那行 `df = pd.read_csv(...)` 抄过去,**完全不需要复制原数据**。

#### 2.2.2 关键改动点

**(1) 新增 `ArtifactStore`** — `src/main/java/.../artifact/ArtifactStore.java`

```java
public class ArtifactStore {
    private final Path artifactsDir;          // host 路径,= workspace/artifacts
    private final String containerMountPath;  // 容器内对应路径,= /workspace/artifacts(sandbox 模式)
                                              // 非 sandbox 模式 = artifactsDir.toAbsolutePath() 字符串

    public ArtifactRef save(String toolName, String csv, List<String> columns, int rows) {
        String id = toolName + "-" + Instant.now().toEpochMilli() + "-"
                    + Integer.toHexString(csv.hashCode() & 0xfffff);
        Path host = artifactsDir.resolve(id + ".csv");
        Files.writeString(host, csv, UTF_8);
        return new ArtifactRef(id, containerMountPath + "/" + id + ".csv",
                               columns, rows, preview(csv, 5));
    }
}

public record ArtifactRef(String id, String agentVisiblePath,
                          List<String> columns, int rows, String previewMarkdown) {}
```

**(2) `QualityTools` 注入 `ArtifactStore`,改返回逻辑** —— 把现在 `StringJoiner` 拼好的 markdown 同时落 CSV:

```java
@Tool(name = "query_quality_by_department_quarter", description = "...")
public ToolResultBlock queryByDepartmentAndQuarter(...) {
    // ... 现有 markdown 拼装 ...
    String csv = renderCsv(quarter, department, rows);
    ArtifactRef ref = artifactStore.save("qd", csv,
        List.of("季度", "部门", "质量分"), rows.size());
    return ToolResultBlock.text(buildHandoffMessage(ref, markdownPreview));
}

private static String buildHandoffMessage(ArtifactRef ref, String preview) {
    return String.format("""
        query_quality_data 查询完成
        - 行数: %d, 列: %s
        - 数据文件: %s
        - 前 5 行预览:
        %s

        ▶ 在 code_interpreter 中读取:
          df = pd.read_csv("%s")
        """, ref.rows(), String.join(", ", ref.columns()),
        ref.agentVisiblePath(), preview, ref.agentVisiblePath());
}
```

**(3) sandbox bind mount 加 artifacts 目录** —— [SandboxProperties.Sandbox](../src/main/java/io/agentscope/examples/harness/a2a/config/SandboxProperties.java#L60) 加 `mountArtifacts = true`(默认开),[FilesystemConfig.sandboxFilesystem()](../src/main/java/io/agentscope/examples/harness/a2a/config/FilesystemConfig.java#L119) 仿照 `mountSkills` 的写法多挂一组:

```java
if (s.isMountArtifacts()) {
    workspaceSpec.getEntries().put("artifacts",
        hostBindMount(workspace.resolve("artifacts")));
}
```

**(4) `ArtifactStore` Bean 注册** —— [InfraConfig](../src/main/java/io/agentscope/examples/harness/a2a/config/InfraConfig.java) 里加:

```java
@Bean
public ArtifactStore artifactStore(Path workspace, SandboxProperties props) {
    Path artifactsDir = workspace.resolve("artifacts");
    Files.createDirectories(artifactsDir);
    String mountPath = props.getSandbox().isEnabled()
            ? props.getSandbox().getWorkspaceRoot() + "/artifacts"   // /workspace/artifacts
            : artifactsDir.toAbsolutePath().toString();              // 宿主绝对路径
    return new ArtifactStore(artifactsDir, mountPath);
}
```

**(5) `SupervisorService.buildToolRegistry()` 用工厂注入** ——

```java
r.put("quality_tools", () -> new QualityTools(artifactStore));
```

**(6) prompt 适配** —— 改两个子智能体 markdown,把"派单格式"段落改成:

[analyze-data.md](../src/main/resources/workspace/subagents/analyze-data.md) 的"派单格式":

```
agent_spawn(
  agent_id="code_interpreter",
  task="""
  查询返回的数据文件为 /workspace/artifacts/qd-xxx.csv
  请用 pandas 读取后计算 ...

  df = pd.read_csv("/workspace/artifacts/qd-xxx.csv")
  """
)
```

并加一句铁律:**"不要把表格内容塞进 task 里,只塞 csv 路径 + 计算需求"**。

[code-interpreter.md](../src/main/resources/workspace/subagents/code-interpreter.md) 加一段"约定":

```
## 数据传递约定

派单消息里出现 `/workspace/artifacts/*.csv` 路径 = 上游已经把查询结果落盘成 CSV。
你的 Python 脚本第一步必须是:

  import pandas as pd
  df = pd.read_csv("/workspace/artifacts/<id>.csv")

绝对不要尝试把派单消息里的 markdown 表格手工解析成 DataFrame —— CSV 路径是
权威数据,markdown 预览只是给人看的。
```

#### 2.2.3 这个方案为什么对

| 维度 | 改进点 |
|---|---|
| **token** | 从"把 24×4 表 ×2 次跨 agent 复制"压到"4 行短引用"。算下来 24 行表 ≈ 800 token,3 次中转就是 2400 token;改完每跳 ~80 token,**节省 95%+** |
| **正确性** | LLM 完全不再触碰数字 → 不存在抄错截断;`DataGroundingHook` 校验直接通过 |
| **可扩展** | 后续要传 Excel / parquet / 图片,加 `MIME / 格式` 字段就行,协议本身不变 |
| **诊断** | `workspace/artifacts/` 目录就是断点截图 —— 任何一步出错都能直接 `cat` 那个 CSV 看上游数据,不用回放对话 |
| **零侵入框架** | 不改 `agentscope-harness`,纯 example 内部演进,跟 harness 主线没耦合 |

#### 2.2.4 路径兼容性细节(必看)

**这个方案要求 `query_quality_data` 写的路径 = `code_interpreter` 看到的路径**,要分情况:

| 模式 | host 写在哪 | 容器内看到的路径 | 一致吗 |
|---|---|---|---|
| **default**(无 sandbox) | `workspace/artifacts/x.csv` | 无容器 —— `code_interpreter` 的 `shell_execute` 直接打到宿主 shell,看到的就是 `workspace/artifacts/x.csv` 的绝对路径 | ✅ `ArtifactStore` 返回宿主绝对路径 |
| **sandbox profile** | 宿主 `workspace/artifacts/x.csv`(通过 bind mount) | `/workspace/artifacts/x.csv` | ✅ `ArtifactStore` 返回容器内路径 |
| **distributed profile**(workspace 走 Redis BaseStore) | Redis hash | ⚠️ `read_file` 在容器内会路由到 `RemoteFilesystemSpec` 但 pandas 的 `pd.read_csv` 走不通这条路 | ❌ **artifact 必须落 host 文件**,distributed 模式下 artifacts 目录单独走本地 FS,别走 Redis |

最后一条:在 `distributed` profile 下,artifacts 不能像 `MEMORY.md` / `skills/` 那样路由进 `JedisBaseStore`。两个 Spring Boot 副本想互看 artifacts,要么把 artifacts 也放共享 NFS / object storage,要么**约束 artifact 只在生成它的副本所在的 task 里被消费**(同一个 A2A taskId 通常打到同一副本,这条天然成立 —— 但要在文档里写明)。

### 2.3 P2(可选,纯 Python 友好) —— code_interpreter 工具包内置 `load_artifact` 跳过 LLM

P1 已经让 LLM 不参与数据搬运,但 LLM 还得**把那行 `df = pd.read_csv(...)` 抄到自己的 Python 脚本里**。如果想连这一步都免掉:

给 `code_interpreter` 子智能体加一条**工具级**约定 —— 在 sandbox `/workspace/artifacts/_LATEST.json` 里维护"最近一次派单时上游引用过的所有 artifact 列表":

```json
{
  "spawned_at": "2026-06-10T15:00:00Z",
  "artifacts": [
    {"id": "qd-2026q1-...", "path": "/workspace/artifacts/qd-2026q1-...csv",
     "columns": ["季度","部门","应用","质量分"], "rows": 24}
  ]
}
```

实现方式:`SupervisorService.registerSubagentFromSpec()` 给 `code_interpreter` 的工厂里加一个 `PreCallEvent` hook,扫描 task 文本里所有 `/workspace/artifacts/*.csv` 路径,写进 `_LATEST.json`。然后 [code-interpreter.md](../src/main/resources/workspace/subagents/code-interpreter.md) 改一段:

```
## 自动 artifact 装载

启动时先 read_file `/workspace/artifacts/_LATEST.json` 把所有 artifact 装成 DataFrame:

  import json, pandas as pd
  meta = json.loads(open("/workspace/artifacts/_LATEST.json").read())
  dfs = {a["id"]: pd.read_csv(a["path"]) for a in meta["artifacts"]}

接着用 dfs["qd-2026q1-..."] 直接索引 DataFrame,不需要从派单消息里抄路径。
```

这相当于 Jupyter 的"上下文变量"风格 —— LLM 写脚本时直接说 `dfs['qd-2026q1-...'].mean()`,数据搬运 100% 在 framework 完成。

**代价**:多一个 hook(~30 行)。**收益**:LLM 几乎不出错,Python 写得更短。**适合**:`code_interpreter` 真正成为分析骨干、单次会话 artifact 多于 3 个的场景。

---

## 3. 决策建议

| 时间 / 风险预算 | 走哪条 |
|---|---|
| **本周想看到效果,且只跑 sandbox profile** | P0 改 yml 一行 + 在 prompt 里写明"看到 placeholder 路径就抄给 code_interpreter" |
| **下一个迭代,有 1-2 天预算** | **P1**(推荐) —— 一劳永逸解决"LLM 抄数据"问题,可在所有 profile 下工作 |
| **`code_interpreter` 会被频繁、深度使用** | 在 P1 之上叠 P2 |

我会建议**直接做 P1**,P0 当过渡可选,P2 等观察一段时间再决定。

---

## 4. 实施 P1 时的小心事项

1. **CSV 编码** 统一 `UTF-8 with BOM` —— `pd.read_csv` 默认 utf-8,但 Excel 打开中文 CSV 要 BOM,落盘文件后续要让分析师手工检查时方便。
2. **artifact GC** —— `workspace/artifacts/` 会无限增长。加一个简单清理:Spring Boot 启动时 `@PostConstruct` 把 7 天前的 artifact 删掉。或者按 `taskId` 子目录组织 (`artifacts/<taskId>/`),任务结束时整体清。
3. **artifact id 必须包含 hash** —— 同一参数二次查询要复用同一个 artifact id,这样 `ResponseCacheHook` 命中时,LLM 给 user 的回答里的 path 还是有效的(否则缓存命中后 code_interpreter 找不到文件)。如果不放心,缓存命中时直接 bypass code_interpreter 派单。
4. **prompt 里要明确禁止"重新拼数据"** —— 不然 LLM 看见 5 行预览会"贴心地"把它们抄进 task,功亏一篑。`DataGroundingHook` 可以增加一条"派给 code_interpreter 的 task 不允许出现工具结果里的小数字"的检测兜底。
5. **回测顺序** —— 改完后先跑 default profile(host 路径),再跑 sandbox(容器路径),再跑 sandbox+distributed(确认 artifact 路径在同副本内闭合)。三组都过再合并。

---

## 5. 参考资料 / 源码索引

- `harness` 自带工具结果落盘:[ToolResultEvictionHook.java](../../../../../agentscope-harness/src/main/java/io/agentscope/harness/agent/hook/ToolResultEvictionHook.java),[ToolResultEvictionConfig.java](../../../../../agentscope-harness/src/main/java/io/agentscope/harness/agent/memory/compaction/ToolResultEvictionConfig.java)
- 共享沙箱文件系统:[SandboxFilesystemSpec.java](../../../../../agentscope-harness/src/main/java/io/agentscope/harness/agent/filesystem/spec/SandboxFilesystemSpec.java),[AbstractSandboxFilesystem.java](../../../../../agentscope-harness/src/main/java/io/agentscope/harness/agent/filesystem/sandbox/AbstractSandboxFilesystem.java),[IsolationScope.java](../../../../../agentscope-harness/src/main/java/io/agentscope/harness/agent/IsolationScope.java)
- 子智能体派单:[AgentSpawnTool.java](../../../../../agentscope-harness/src/main/java/io/agentscope/harness/agent/tool/AgentSpawnTool.java) —— 注意 task 只能是字符串,目前没有结构化负载位
- 本仓库现有的 sandbox 接线:[FilesystemConfig.java:117-148](../src/main/java/io/agentscope/examples/harness/a2a/config/FilesystemConfig.java#L117-L148),bind mount 的写法可以直接照抄给 `artifacts` 目录
- 子智能体工厂复用 sandbox spec 的代码:[SupervisorService.java:264-271](../src/main/java/io/agentscope/examples/harness/a2a/service/SupervisorService.java#L264-L271)

---

## 6. 工具规模化 —— 不要让每个工具都改造

### 6.1 当前 P1 实现的问题

P1 落地版让 `QualityTools` 的 4 个方法每个都长这样:

```java
@Tool(...)
public ToolResultBlock queryByDepartmentAndQuarter(
        RuntimeContext ctx,                              // ← 多出来的参数
        @ToolParam("quarter") String quarter, ...) {
    TableData table = ...;
    return emit(ctx, "...", "qdq", filters, table);     // ← 多出来的 emit 调用
}
```

**4 个 demo tool 改起来没问题,但**:

| 工具数量 | 改造代价 | 风险 |
|---|---|---|
| 4(本 demo) | 一次性,1 小时 | 低,可控 |
| 20 | 一次性,但每次新增 tool 都要遵循约定 | 中,容易漏 |
| **80+(真实生产)** | **每个 tool 业务方都要懂 ArtifactStore / 表头声明 / CSV 格式 / 缓存键约束** | **高,违反 [[feedback_tool_coupling]] 原则** |

记忆里这条原则反复强调过:**"Don't propose tools emit structured envelopes for validation hooks; doesn't scale to 80+ production tools."** 同理适用 artifact —— **不应该让每个工具都关心"我要不要写 artifact / 怎么写"**。

### 6.2 P3 方案 —— 让工具回归原貌,artifact 完全由 hook 透明接管

把 artifact 写入挪进**一个 hook**,工具回归"只返回 markdown / JSON / 文本"的最朴素形态。

```
工具(原样,不改)              hook(全局,新增)                  下游 agent
─────────────────              ────────────                       ──────────
@Tool                          PostActingHook                      看到 placeholder:
queryByDepartmentAndQuarter()  ↓                                   📦 path: /workspace/artifacts/...
  return ToolResultBlock.text(    1. 大小 > 阈值?                  ↓
    """                           2. 内容像 markdown table?         读它即可
    | 季度 | 部门 | 质量分 |     3. 是,落 CSV(从 markdown 解析)
    | ... 24 行 ... |""")        4. 替换 result 为 handoff 消息
```

### 6.3 hook 端怎么做(技术细节)

**架构**:增强版 `ToolResultEvictionHook`(harness 自带的)—— 它已经有"超过阈值时写 FS + 替换 placeholder"的骨架,我们派生 / 替换它,只把"按字符数 evict"改成"**按内容形态 evict**"。

```java
public class ArtifactHandoffHook implements Hook, RuntimeContextAware {

    // 工具不需要任何改动,这里识别"看起来是表格"的工具结果
    @Override
    public Mono<PostActingEvent> onPostActing(PostActingEvent e) {
        ToolResultBlock result = e.getToolResult();
        String text = extractText(result);

        // ===== 规则:什么样的结果值得 artifactize? =====
        TabularData parsed = tryParseTabular(text);
        if (parsed == null || parsed.rowCount() < ROWS_THRESHOLD) {
            return Mono.just(e);     // 小表格 / 非表格 → 不动
        }

        // ===== 这才是 P1 的 "emit" 流程,但完全在 hook 内 =====
        ArtifactContext ctx = ArtifactContext.from(runtimeContext);
        ArtifactRef ref = artifactStore.save(ctx, e.getToolName(),
            parsed.toCsv(), parsed.columns(), parsed.rowCount(), parsed.preview(5));
        e.setToolResult(buildHandoff(ref));    // 替换原结果
        return Mono.just(e);
    }

    /** 三种识别策略,按可信度从高到低,任一命中即提取 **/
    private TabularData tryParseTabular(String text) {
        // 策略 A:工具自己用 ToolResultBlock 的 metadata 显式声明 "tabular":
        //   ToolResultBlock.text(...).withMetadata("schema", "tabular,csv:...")
        //   只对真正需要精确控制的工具用,默认不需要
        TabularData metaDriven = parseFromMetadata(toolResult);
        if (metaDriven != null) return metaDriven;

        // 策略 B:markdown 表格识别(| col | col | / |--|--|)
        // 80% 的"返回表格"的工具天然就这么写,因为 markdown 表格是 LLM 友好的默认
        TabularData mdTable = parseMarkdownTable(text);
        if (mdTable != null) return mdTable;

        // 策略 C:JSON 数组 of objects([{...},{...}])
        TabularData jsonRows = parseJsonRows(text);
        if (jsonRows != null) return jsonRows;

        return null;     // 不像表格,放过
    }
}
```

### 6.4 三种识别策略对比

| 策略 | 工具改造 | 准确度 | 误报风险 |
|---|---|---|---|
| **A. metadata 声明** | 工具加一行 `withMetadata("schema","tabular")` | 100% | 0 |
| **B. markdown 表格 regex** | **零改动** | 95%(漏掉非常规格式) | 低 |
| **C. JSON 数组** | **零改动** | 98% | 低 |

**默认走 B+C 组合,够覆盖 80%+ 的 tabular 工具**。剩下的 20% 不规则输出工具:
- 要么不需要 artifact 化(就是几个数字 / 一段文本,本来就该 inline)
- 要么用策略 A 显式打 tag(成本:加 1 行)

### 6.5 改造对比 —— A 方案 vs P3

| 维度 | A 方案(当前 P1) | P3 方案(本节) |
|---|---|---|
| 工具方法签名 | 加 `RuntimeContext ctx` 参数 + 改 emit 调用 | **零变化** |
| 工具内部逻辑 | 拆出 `TableData` + 走 `emit(...)` | **零变化** |
| 新增 tool 时门槛 | 需要懂 `ArtifactStore` / 表头声明 / emit 协议 | **不需要懂任何 artifact 概念** |
| 单工具改造 LOC | ~10-30 行 | 0 |
| 80 个 tool 总改造 LOC | ~800-2400 | 0(只在 hook 一次性 ~200 行) |
| 触发标准 | 显式调用 | 自动(by 形态识别) |
| 控制粒度 | 工具级,精细 | 全局策略 + per-tool override |
| 跨用户隔离 | 同(都靠 `ArtifactStore`) | 同 |
| **何时选** | 工具少 + 想精确控制每个 tool 的格式 | **工具多 / 业务方不该懂 artifact** |

### 6.6 演进路径

1. **当前 demo(已实施 A 方案)** —— 4 个工具,A 方案够用,演示价值高(代码读起来直观)。
2. **生产推广前** —— 把 A 方案改造的 4 个工具回退成"只返回 markdown",同时实施 P3 hook。同一个 demo 行为不变,但 80+ 工具加进来时**零成本**接入。
3. **特殊工具兜底** —— 极少数确实需要精确控制 CSV 头 / 分隔符 / 编码的工具,用策略 A(metadata 声明)显式定制。

### 6.7 落地清单(P3)

| # | 文件 | 改动 |
|---|---|---|
| 1 | `ArtifactHandoffHook.java`(新) | ~200 行:markdown / JSON 表格识别 + 调 `ArtifactStore.save` + replace result |
| 2 | `SupervisorService.build()` / `registerSubagentFromSpec` | 把 `b.hook(new ArtifactContextBindingHook())` 替换 / 增补为 `b.hook(new ArtifactHandoffHook(artifactStore))`,priority 设为 11(略低于 DataGroundingHook 的 15) |
| 3 | `QualityTools.java` 回退 | 删 `RuntimeContext ctx` 参数 + 删 `emit` 的 artifact 分支,只留"产出 markdown"逻辑 |
| 4 | 测试 | 新增 `ArtifactHandoffHookTest`:输入 markdown 表 + 模拟 PostActingEvent → 断言 result 被替换 + CSV 落盘正确 |

预计 ~300 行代码(含测试),换来 **80 个生产工具零改造接入 artifact handoff**。

### 6.8 决定建议

- **本 demo 当前状态**:保留 A 方案,因为演示链路清晰、单元测试好理解,适合作为"概念验证"。
- **真要上生产推广到 80+ 工具**:**优先做 P3**,A 方案的 4 个工具回退为普通 markdown 输出,统一走 hook。
- **混合方案**:绝大多数走 P3(零侵入),个别有强格式需求的工具走 A(metadata 声明)—— 这是 harness `ToolResultEvictionHook` 现在的思路的自然延伸。

记忆 [[feedback_tool_coupling]] 的原则在 artifact 场景下重述一次:**hook 是为"对所有工具通用的横切处理"准备的;工具是为"业务计算"准备的。把数据搬运 / 序列化 / 落盘塞进工具实现,就是把横切逻辑硬编码到业务里,规模上去必爆。**

