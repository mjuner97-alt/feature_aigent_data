# script_exec 统一执行能力设计：SQL 调配 + 明细下载合并

> 状态：设计稿（未实施）
> 日期：2026/09/14
> 关联：`SkillFixedToolGuardHook`（2026/09/14 固定流程机械兜底）、`docs/table-mertics/`、`docs/prompt/python-exec-optimization-plan.md`

---

## 一、背景与目标

当前 Q2-1 这类"指标计算 + 明细下载"场景，Skill 要写两步工具调用：

```text
Step 2: script_exec(scriptId="q2_1_metrics_by_dept_version", params={...})   # 算指标
Step 3: sql_registry_exec(sqlId="q2_1_metrics_by_dept_version",
        params={...}, downloadFilename="q2_1_明细.csv")                      # 下载明细
```

两次调用执行**同一条 SQL**（q2_1 脚本内部 SQL 与 sql_registry 注册的 SQL 是重复维护的），
LLM 要正确串两个工具、两次传对 params，任何一步走样都会触发 2026/09/14 那类漂移事故。

**目标**：让 `script_exec` 一次调用完成"注册 SQL 调配 + 指标计算 + 明细下载"：

1. **SQL 调配进脚本**：Python 脚本能按 `sqlId`（原来 `sql_registry_exec` 用的那个 ID）
   取出 `sql_registry` 里注册好的 SQL 并执行，不再各自维护一份 SQL；
2. **downloadFilename 落在脚本里**：`downloadFilename="q2_1_明细.csv"` 由脚本内部设置，
   不再是 LLM 传的工具参数；
3. **其余 params 由模型按问题传入**（dept / version 等，仍走 params_schema 白名单）；
4. **最终可替代 sql_registry_exec**（迁移路线见 §七）。

**附带收益（很重要）**：明细行只进下载块、不进 LLM 可见输出。当前 sql_registry_exec 把
80 行全量 markdown 表返回给 LLM（占上下文），合并后 LLM 只看汇总指标，明细直接落库生成短链。

---

## 二、现状梳理

| 组件 | 位置 | 职责 |
|---|---|---|
| `ScriptExecTool` | `v2/tools/ScriptExecTool.java` | scriptId → 查 `script_registry`（Gauss）→ 白名单校验 → 注入 DB env（gauss 走 GAUSS_JDBC_*，JPype）→ fork/python3，stdin 传 JSON params，stdout 原样返回 |
| `SqlRegistryExecTool` | `v2/tools/SqlRegistryExecTool.java` | sqlId → 查 `sql_registry`（Gauss，`mapper.gauss.SqlRegistryMapper`）→ 白名单校验 → JDBC 执行 → markdown 表渲染；`downloadFilename` 参数 → `DownloadContentService.create(md, filename, "text/csv")` 生成短链附结果末尾 |
| `DownloadContentService` | `v2/service/DownloadContentService.java` | content 落 `url_shortener` 表（Gauss）→ 16 位 BASE62 shortCode → `/redirect/download?shortCode=xxx`；markdown 表自动转标准 CSV；5MB 上限 + MIME 白名单 |
| `_gauss_jdbc.py` | `workspace/scripts/` | JPype + opengauss-jdbc 查询助手（`query_gauss(sql, params)`，`:name` 占位符 → JDBC `?`） |
| q2_1 脚本 | `workspace/scripts/555153205/q2_1_metrics_by_dept_version.py` | 内嵌硬编码 SQL → pandas 算指标 → 输出 markdown 汇总表 + `json: {...}` 行 |

关键事实：

- `sql_registry` 表在 **GaussDB**，q2_1 脚本已经具备 Gauss JDBC 访问能力（`query_gauss`），
  **python 侧读取 sql_registry 在技术上零障碍**；
- `url_shortener` 表也在 Gauss，但短链生成逻辑（BASE62 + 防碰撞 + baseUrl 拼接）在 Java
  `DownloadContentService`，**不应让 python 重复实现**（两处生成逻辑必然漂移）；
- ScriptExecTool 的 stdout 已重定向到临时文件（防 Windows 管道死锁），大输出无管道限制；
- `SkillFixedToolGuardHook`（priority 14）会在 Skill 固定 scriptId 时静默纠正幻觉 ID ——
  合并后 Skill 只写 scriptId，正好落在已有兜底范围内。

---

## 三、方案对比

### 方案 A：stdout 下载块协议 + Java 后处理（推荐）

Python 脚本在 stdout 里输出特殊标记块声明下载内容；`ScriptExecTool` 在
`runProcess` 拿到 stdout 后解析、剥离标记块、调 `DownloadContentService` 生成短链，
把 "📥 下载链接" 追加到工具结果。

```text
脚本 stdout:
  | 总数 | 已打分 | ... |          ← LLM 可见的汇总表
  json: {...}                       ← 已有约定，程序解析用
  <<<DOWNLOAD_META>>> {"filename":"q2_1_明细.csv","mimeType":"text/csv"}
  <<<DOWNLOAD_CONTENT>>>
  |项目编号|项目名称|...80行...|    ← 只进下载块，不进 LLM 上下文
  <<<DOWNLOAD_END>>>
```

- 优点：下载生成逻辑单一维护在 Java（与 sql_registry_exec 完全同一条链路，
  `RedirectController` / 5MB 上限 / MIME 白名单 / markdown→CSV 转换全部复用）；
  python 侧只加"输出"，不加新依赖。
- 缺点：ScriptExecTool 需要新增 stdout 协议解析。

### 方案 B：Java 端把注册 SQL 的查询结果喂给 python

`script_exec` 新增 `sqlId` 参数，Java 查 `sql_registry` 执行 SQL，把 rows 塞进 stdin
给脚本算指标。

- 否决理由：把一次执行拆成两半（Java 跑 SQL + python 跑计算），跨数据源、多段 SQL、
  计算中间需要再查询的场景全被堵死；且 `sqlId` 变成 LLM 可传的顶层参数，
  重新打开"幻觉 ID / 动态发现"的口子（正是 2026/09/14 事故刚堵上的）。

### 方案 C：python 直接写 url_shortener 表

脚本自己生成 shortCode 插表。

- 否决理由：短链生成逻辑（BASE62、碰撞重试、baseUrl、filename/MIME 规则）在 python
  里再实现一份，必然与 Java 漂移；且脚本要拿到 url_shortener 表写权限，攻击面变大。

**结论：方案 A。** python 只负责"产出内容 + 声明文件名"，落库和短链全走既有 Java 服务。

---

## 四、详细设计（方案 A）

### 4.1 stdout 下载块协议

标记必须**行首**出现（防止业务数据里偶然出现的同名字符串误触发）：

```text
<<<DOWNLOAD_META>>> <单行 JSON>
<<<DOWNLOAD_CONTENT>>>
<任意多行内容，原样保留>
<<<DOWNLOAD_END>>>
```

- `DOWNLOAD_META` JSON 字段：`filename`（必填）、`mimeType`（可选，默认 `text/csv`，
  受 `DownloadContentService` 白名单约束）。`downloadFilename` 就这样**固化在脚本里**，
  LLM 不再传。
- 一个 stdout 里**允许多个下载块**（例如脚本同时出明细 CSV + 报告 md）。
- **下载块的位置即链接行的位置（原位替换，非末尾追加）**：Java 剥离下载块后，
  在块的**原位置**插入 `📥 [filename](url)` 链接行。脚本通过控制下载块与
  html/echarts 块的 print 顺序，即可自由排列"图表在上、链接在下"或"链接在上、
  图表在下"（以及多个图 + 多个链接的任意交错）——面向 PPT 拼版时直接调整
  print 顺序即可，不需要任何 Java 侧改动。
块内内容**只用于落库生成短链，既不进 LLM 上下文也不出现在工具结果里**；
工具结果里最终只追加一条可点击的 Markdown 下载链接（见 §4.2）。
前端展示链路（`/ai/chat`，见 §4.6）对 script_exec 结果有专门接管：最终展示的是
脚本 **print() 出来的 stdout 内容**（由 `ScriptExecOutputExtractor.extractStdout`
从结果信封中切出），所以下载链接必须落在 stdout 段内才能到达用户，详见 §4.6。
- 解析失败的块（缺 END / META 非法 JSON）：保留原文并 log.warn，**不生成链接也不断言失败**，
  宁可多给 LLM 看原文也不吞数据。

### 4.2 ScriptExecTool 改造（Java）

1. **构造注入 `DownloadContentService`**（`V2ToolConfig` 装配处加一个参数，与
   SqlRegistryExecTool 同款）。
2. `runProcess` 读到 stdout 后，调新私有方法 `extractDownloads(stdout)`：
   - 正则扫描三个标记行，把每个下载块**原位替换**成一条可点击的 Markdown 链接
     （位置 = 下载块在 stdout 中的位置，脚本借此控制图/链接的上下排列，见 §4.1）：
     ```text
     📥 [q2_1_明细.csv](/redirect/download?shortCode=xxxx)
     ```
     替换发生在 `formatResult` 拼 `─── stdout ───` 信封**之前**（即链接行是
     stdout 变量的一部分）——`/ai/chat` 展示链路只取 stdout 段（§4.6），
     落在信封外用户就看不到了。
   - 对每个块调 `downloadContentService.create(content, filename, mimeType)`，
     拼 `buildDownloadUrl(shortCode)`；
   - 链接行**不带任何附加说明**：文件名即锚文本（来自 META 的 `filename`），
     前端 markdown 渲染器直接渲染成可点击链接。刻意**不**沿用 sql_registry_exec
     的"下载链接 + 落库说明"两行文案——说明文字对用户是噪音。
3. `executeForDebug`（HTTP 调试链路）天然复用同一 `scriptExec`，无需单独改。
4. `@Tool` description 补一句：`脚本可自带明细下载块，调用方无需传下载参数`。

### 4.3 SQL 调配：`_sql_registry.py` 共享模块

新增 `workspace/scripts/_sql_registry.py`（与 `_gauss_jdbc.py` 同级，靠已有的
`PYTHONPATH=<workspace>/scripts` 注入可被子目录脚本 import）：

```python
from _sql_registry import run_registered_sql

rows = run_registered_sql(
    "q2_1_metrics_by_dept_version",            # sqlId：原来 sql_registry_exec 用的 ID
    {"dept": ["杭州开发二部"], "version": ["2026年7月份版本"]},
)
```

内部实现：

1. `SELECT sql_template, params_schema, datasource FROM sql_registry WHERE sql_id = :id`
   （走 `query_gauss`，env 已由 ScriptExecTool 注入）；
2. 按 `params_schema` 做**与 Java 同规则**的校验（多余参数拒绝、必填缺失报错——
   校验逻辑虽然双份，但这是安全闸门，双份比缺一份好）；
3. 按 `datasource` 字段分派：
   - `gauss`：`query_gauss(sql_template, params)`（JPype，主路径）；
   - `mysql` / `clickhouse`：读 `MYSQL_DB_URL` / `CLICKHOUSE_DB_URL` env，
     `pandas.read_sql`（前提：脚本 `script_registry.datasources` 已声明该库，
     Java 才会注入对应 env —— 最小权限模型不变）；
4. 未注册的 sqlId → 明确报错 `ERROR sqlId 'xxx' 不在 sql_registry 中`，exit 非 0。

**sqlId 怎么传？** 两个子模式，按场景选：

| 子模式 | 形态 | 适用 |
|---|---|---|
| **a. 固定在脚本内（推荐）** | 脚本里写死 `run_registered_sql("q2_1_metrics_by_dept_version", ...)`，`downloadFilename` 同样写死 | Skill 固定流程场景。Skill 正文只出现 scriptId，幻觉防护由 SkillFixedToolGuardHook 的 scriptId 分支覆盖，与现有机制零新增 |
| b. 作为 params 传入 | `params_schema` 声明 `sqlId`，脚本透传给 `run_registered_sql` | 动态发现场景（无 Skill 固定 ID）。**注意**：这等于把 ID 选择权还给 LLM，必须等 sql_registry_exec 真正下线、且确认需要时再开，并同步给 SkillFixedToolGuardHook 加 `script_exec` params 内层 sqlId 的纠正分支 |

本设计按子模式 a 落地（与"Skill 固定流程优先"的整体哲学一致）；q2_1 演进为
"注册 SQL 的计算 + 下载包装器"。

#### 4.3.1 多 SQL（多下载链接）的参数来源

一个脚本产出多个下载块（= 调配多个注册 SQL）时，参数流转规则：

```text
模型 ──一次 script_exec──▶ params（flat，params_schema 白名单的并集）
                              │
                              ▼
                     脚本（开发人员写的分派逻辑）
                     ├─ run_registered_sql("sql_A", {取 params 的 dept/version})
                     ├─ run_registered_sql("sql_B", {取 params 的 dept + 派生值})
                     └─ run_registered_sql("sql_C", {常量/脚本内计算值})
```

- **模型只调一次工具、只传一份 flat params**；各 SQL 需要什么参数、怎么路由/
  派生（如按 dept 查部门编码再喂 SQL_B），全部写在脚本里，由开发人员保证；
- `params_schema` 声明**并集**（如 `dept` / `version` / 可选的 `metric_type`），
  白名单校验仍是单点（ScriptExecTool 一处）；
- 有的 SQL 参数是脚本内部常量或从其他 SQL 结果派生的（如先查口径日期再喂明细 SQL），
  这类**不进 params_schema**，模型无感；
- 若两个 SQL 确实需要模型分别指定不同值（少见），再考虑嵌套参数
  （`params={"a_filter":{...},"b_filter":{...}}`，白名单在顶层 key 一层），
  原则仍是"一次调用一份 params"。

对比旧模式：多个 sql_registry_exec 调用 = 模型要 N 次传对参数（N 倍出错面）；
新模式把 N 次参数传递压成脚本内部代码，模型只负责 1 次。

### 4.4 q2_1 脚本改造示意

```python
from _gauss_jdbc import query_gauss          # 保留（_sql_registry 内部依赖）
from _sql_registry import run_registered_sql

DOWNLOAD_FILENAME = "q2_1_明细.csv"          # downloadFilename 落在脚本里

def main():
    params = json.loads(sys.stdin.read() or "{}")
    # ... dept/version 解析同现状 ...

    # 1. 调配注册 SQL（删掉脚本内硬编码的那份 SQL）
    rows = run_registered_sql(
        "q2_1_metrics_by_dept_version",
        {"dept": depts, "version": versions},
    )
    df = pd.DataFrame(rows)

    # 2. 算指标（同现状）
    ...

    # 3. 汇总表 + 可渲染块（script_exec 约定：输出必带 html/echarts，
    #    /ai/chat 的 ChatScriptExecResultHook 据此接管 stdout、末尾追加展示）
    print(f"| 总数 | 已打分 | 达标数 | 打分率 | 达标率 |")
    print(f"|---:|---:|---:|---:|---:|")
    print(f"| {total} | {scored} | {passed} | {scored_pct}% | {passed_pct}% |")
    print(f'json: {{"total":{total},...}}')
    print("```echarts")
    print(json.dumps({...打分率/达标率柱状图...}, ensure_ascii=False))
    print("```")

    # 4. 明细进下载块：80 行只落库，不占 LLM 上下文；
    #    下载块 print 在 echarts 块之后 → 最终展示"图在上、下载链接在下"；
    #    PPT 拼版要"链接在上、图在下"时，把这段挪到 echarts print 之前即可
    if total:
        detail_md = df.to_markdown(index=False)
        print(f'<<<DOWNLOAD_META>>> {json.dumps({"filename": DOWNLOAD_FILENAME}, ensure_ascii=False)}')
        print("<<<DOWNLOAD_CONTENT>>>")
        print(detail_md)
        print("<<<DOWNLOAD_END>>>")
```

### 4.5 Skill 改写示意（q2_1_script_then_download_demo）

两步并为一步，正文只剩一个工具调用：

```markdown
### Step 2：执行脚本（已内置明细下载）

必须直接调用一次 `script_exec`：

```text
script_exec(
  scriptId="q2_1_metrics_by_dept_version",
  params={"dept":"杭州开发二部","version":"2026年7月份版本"}
)
```

### Step 3：回复用户

- 工具结果会被系统接管（你看到的是占位符）：指标表与下载链接由系统自动附在回答末尾；
- 只需根据问题写简短的业务总结，**不要**复述图表内容、不要编造下载链接或 shortCode。
```

Skill 不再出现 `sqlId=`，`SkillFixedToolGuardHook` 只按 scriptId 布防——正好
落在已验证的纠正分支上，无新增防护逻辑。

### 4.6 `/ai/chat` 最终展示链路协同（关键）

前端（`frontend/src/api/chat.ts`）走的是 **`/ai/chat`**（v1 controller →
`ChatStreamServiceImpl`），这条路径 `ChatStreamServiceImpl:382` 会置
`ChatScriptExecResultHook.ENABLED_CTX_KEY=true`，对 script_exec 结果有专门接管：

```text
ScriptExecTool 结果（信封: [script_exec] header + ─── stdout ─── 段 + ─── stderr ─── 段）
  → ChatScriptExecResultHook (priority 50) 取 stdout 段（只有这段到达用户）
  → 含可渲染块 (```html / ```echarts) —— script_exec 的脚本默认都含：
      registry.register(整个 stdout) + 工具结果替换成占位符（LLM 看不到内容）
      → 最终回答层 resolveAndAppendCurrentResults 把注册内容追加在模型回答之后
```

由此推出两个结论：

1. **链接必须落在 stdout 段内**（§4.2 已改）：用户最终看到的"服务端追加内容"
   全部来自 stdout（即脚本 print 出来的内容），拼在信封外的任何东西都到不了用户。
   排列自由度：同一脚本内图/链接的上下顺序 = print 顺序（原位替换，§4.1）；
   多次 script_exec 调用的产出按工具调用顺序追加。唯一边界：这些内容整体位于
   模型回答文本**之后**（服务端末尾追加机制），不能插进模型文本中间——
   PPT 拼版场景模型文本留一句引导即可。
2. **`ChatScriptExecResultHook` 无需任何改动**：script_exec 的脚本默认都带
   html/echarts 可渲染块（不含的不会用 script_exec），可渲染分支必定触发 →
   Java 追加在 stdout 里的下载链接行随注册内容一起追加到最终回答末尾。

   且这比"指望模型复制链接"更可靠：该分支把工具结果整个换成占位符，
   **LLM 从头到尾看不到链接行**，链接 100% 由服务端展示——模型既抄不漏也
   改不坏 shortCode。模型可见的只有占位符
   （"[系统内部：已接管可渲染内容…仅根据其余执行结果回答用户]"），
   因此指标数字也是随注册 stdout 直接到达用户，不经模型转述。

   唯一注意点：无渲染块且无链接的 script_exec 输出会走 `if (!found) return;`
   原样透传给 LLM——这不是本方案的使用场景（用户约定），不处理。

---

## 五、安全与边界

| 维度 | 约束 |
|---|---|
| params 白名单 | 不变：仍由 `script_registry.params_schema` 校验，多余参数拒执行。downloadFilename 不是参数，LLM 无法注入文件名 |
| MIME 白名单 | `DownloadContentService.ALLOWED_MIME` 继续生效 |
| 内容上限 | 5MB（`MAX_CONTENT_BYTES`）超限抛错 → Java 捕获后在工具结果里如实报错 |
| 标记伪造 | 标记须行首匹配；脚本本身是开发人员注册的白名单文件，LLM 无法让 stdout 出现任意内容（它只能传 params）。查询结果数据里若偶然含标记字样，因不在行首也不触发 |
| SQL 权限 | `_sql_registry` 只读分派 sql_registry 里注册的 SQL；sqlId 固定在脚本内，LLM 换不了 |
| DB 凭据 | env 注入模式不变（最小权限：datasources 未声明的库不注入） |

---

## 六、需要同步改动的注册点

1. `V2ToolConfig`：`ScriptExecTool` bean 增加 `DownloadContentService` 参数；
2. `workspace/scripts/`：新增 `_sql_registry.py`；改造 `555153205/q2_1_metrics_by_dept_version.py`
   （输出补 html/echarts 可渲染块，对齐"script_exec = 可渲染输出"的既有约定）；
3. `ChatScriptExecResultHook`：**无需改动**（§4.6——可渲染分支自动携带 stdout 内的
   下载链接；明确写在这里防止后续误判为缺口）；
4. `ScriptExecToolTest`（如无则新建）：下载块解析的单测——
   正常块剥离+链接追加（且链接在 stdout 段内）、多块、缺 END 容错、META 非法 JSON 容错、
   无块时输出原样；
4. `docs/table-mertics/`：q2_1 脚本说明同步。

---

## 七、替代 sql_registry_exec 的迁移路线

**Phase 1 — 能力合并（本设计主体）**
script_exec 具备下载 + SQL 调配；q2_1 脚本 + demo skill 改造；单测 + E2E 验证
（重放命令沿用 2026/09/14 事故同款问题，验收：一次 script_exec 调用同时返回指标与下载链接）。

**Phase 2 — Skill 收敛**
逐个把"script_exec + sql_registry_exec"双步 Skill 改为单步；每个改完跑一次 E2E。

**Phase 3 — sql_registry_exec 下线（评估后执行）**
前置条件：所有活跃 Skill 已无 `sql_registry_exec` 调用。清理清单：
- `V2ToolConfig` 中该工具的注册（主 agent + SubagentRegistrar 各挂载点）；
- `tool_routing` 元数据中 SQL 类条目的处置（保留供 tool_index 展示则只删执行入口，
  或整体标记 enabled=false）；
- `SkillFixedToolGuardHook` 的 `sql_registry_exec` 纠正分支保留无害，可一并清理；
- **注意 target/classes 复活坑**（[[tool_routing_cutover]] 经验）：删类后 clean 重编译。
- 保留 `sql_registry` 表本身与 `_sql_registry.py` 的读取链路（脚本仍要调配 SQL）。

**是否真的下线？** 建议观察期后再决定：若仍存在"纯 SQL、无计算、动态发现"场景
（无 Skill 固定 ID、靠 tool_index 挑 sqlId），sql_registry_exec 仍是合理载体；
可只做 Phase 1/2，把它降级为"仅动态发现场景使用"。

---

## 八、风险与开放问题

1. **校验逻辑双份**（Java params_schema 校验 + `_sql_registry` 内层再校验）：
   接受。安全闸门宁重复勿缺失；后续可考虑把校验下沉到注册时一次性完成。
2. **markdown→CSV 转换对齐**：`MarkdownTableConverter` 现按 sql_registry_exec 的
   输出头（`[sql_registry_exec] ...` 剥离规则）实现，script 侧下载块内容不含该头，
   转换路径不受影响，但需在单测里补一条 script 产 markdown 表 → CSV 的用例。
3. **`df.to_markdown` 依赖 tabulate**：plan-b 镜像需确认已装（未装则
   `pip install tabulate` 进镜像，或手写 markdown 表渲染）。
4. **超时**：下载块只是 stdout 多打几行，不增加执行耗时；5MB 内的明细追加可忽略。
5. **并发/重复下载链接**：每次调用生成新 shortCode，与现状 sql_registry_exec 行为一致。
