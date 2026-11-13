# 脚本表格样式上移展示层统一方案（复用 HtmlReportRenderer，不依赖 hooks）

日期: 2026-11-12
状态: 已实施（2026-11-12，本机 915 单测全过；内网 E2E 待验）
实施落地: `ScriptHtmlSanitizer.java`（新增）、`ScriptExecTool.java`（:918 出口接净化）、
`HtmlReportRenderer.java`（TABLE_CSS 抽取 + 语义类 + scriptTableCss() + injectScriptTableCss()）、
`ChatStreamServiceImpl.java` / `V2ChatStreamServiceImpl.java`（各 2 处注入点）、
测试 `ScriptHtmlSanitizerTest` / `HtmlReportRendererScriptTableCssTest`。
实施修订: ① §4.4 兜底注入取消——混排完整文档 iframe 的 srcdoc 本身就是
renderCompleteHtml→assembleHtml 产物，自带 REPORT_CSS；② sanitizer 增加
`id="report"`→`class="unified-table"` 外壳归一（_html_table.py 现版 wrapper 是
`<div id="report">`，不归一会造成长任务 DOM 嵌套重复 id）；③ chat 注入点为 4 处
（v1/v2 各自的 resolveAndAppend 后 + 直接执行短路轮 sendText 前）。
前作: `docs/202610/DataFrame通用HTML表格导出方案.md`、`docs/202611/个性化HTML表格兼容统一样式方案.md`、
`docs/202611/统一样式片段长任务样式污染修复方案.md`

## 1. 现象与需求

- 问数单脚本 chat 前端展示正常；串进长任务（Skill Job / skill-flow）后，
  脚本输出的 `<style>` 仍影响 Long Task 汇总文档的样式统一。
- **需求**：复用 `HtmlReportRenderer` 的 REPORT_CSS 单一真相源，在**最终展示时刻**
  做统一样式处理（对齐 ChatScriptExecResultHook 已有的"截断/替换、最后展示层恢复"模式）；
  **不再在 Python 脚本内做统一样式调整**。

## 2. 根因：脚本 `<style>` 在双宿主下必然漂移

两条管线在 script_exec 之后分叉：

```
ScriptExecTool stdout
  ├─ chat:      ChatScriptExecResultHook 截断/登记 → ChatStreamServiceImpl:867
  │             resolveAndAppendCurrentResults 恢复原文 → ```html 围栏
  │             → Markdown.vue 片段 iframe（脚本 <style> 是唯一皮肤）
  └─ 长任务:    SkillJobScheduler:527/532 或 FlowCoordinator runScriptDirect:1062
                → ReportMarkdownComposer → HtmlReportRenderer.render：
                片段 <style> 提升进 head（java:1147-1157，REPORT_CSS 之前）
```

`unified-table` 重命名只解决了 id 撞名；脚本 CSS 与 REPORT_CSS 是两份手工对齐的
CSS，重叠属性报告胜、**不重叠属性（字体/margin/hover/td color/标题字号）继续漂移**。
只要脚本还输出 `<style>` 就无法根治。

**hooks 不是可靠切入点**：hook 依赖 `ENABLED_CTX_KEY`（chat 入口显式设置），
且 skill-flow 的 Python 节点 `runScriptDirect` 直接调 `scriptExecTool.executeForFlow`
——**不走 LLM、不走 hooks**。样式治理必须放在所有路径的公共必经点上。

## 3. 方案 v2：剥在工具出口、注在展示边界，全部不依赖 hooks

公共必经点有两个（均与 hooks/LLM 无关）：

```
① 所有脚本产物唯一出口:  ScriptExecTool.executeScript → resultText
② 两类最终展示汇聚点:    chat = ChatStreamServiceImpl resolveAndAppendCurrentResults(:867) 之后
                         报告 = HtmlReportRenderer（REPORT_CSS 已内置，天然汇聚）
```

```
Python 脚本（render_html_custom 已改透传, render_html_table 不动）
      │ stdout
      ▼
[改1] ScriptExecTool 出口 Sanitize（剥 + 包壳）
      剥: ```html 块/完整文档内 <style>/<script> 全部剥除
      包: 含 <table> 的 html 块包 <div class="unified-table"><div class="table-wrap">…
      → 四个消费方 (chat hook / Skill Job / skill-flow / 调试页) 拿到的都是
        「裸结构 + 语义类」，旧脚本残留样式、AI 不经工具的样式从此进不了任何宿主
      │
      ├─[改2] chat 最终展示边界注入（ChatStreamServiceImpl:867 之后）
      │     对最终回答文本【扫描】```html 围栏块与 <!DOCTYPE 完整文档，
      │     注入 <style> + HtmlReportRenderer.scriptTableCss()（同回答去重）
      │     ★ 扫描文本而非依赖 hook 登记: hook 未启用/不走 LLM 的入口同样生效
      │
      └─[改3] 报告侧零注入
            Skill Job / skill-flow 报告由 HtmlReportRenderer 用 REPORT_CSS
            唯一上色；脚本 CSS 已在改1 剥净 → 【根治】
```

### 4.1 改1：ScriptExecTool 出口 `ScriptHtmlSanitizer`（唯一 choke point）

`ScriptExecTool` 三个入口（chat / `executeForFlow` / `executeForDebug`）都汇到
`executeScript → resultText`，在此统一处理：

1. **剥**：```html 围栏块内、完整文档内的 `<style>`/`<script>` 全部剥除
   （正则模式对齐 `HtmlReportRenderer.STYLE_BLOCK`，独立定义避免 report 包反向依赖）；
2. **包壳**：剥后含 `<table>` 的块包 `<div class="unified-table"><div class="table-wrap">…</div></div>`
   —— 因 `render_html_custom` 已透传不再包壳，外壳职责移到 Java；
3. 下载块（`<<<DOWNLOAD_…>>>`）、json/markdown 输出、exit 信封不动；
4. `_html_table.render_html_table` 自带的 `<style>`/wrapper 同样被剥/被收敛——
   **Python 文件零改动**（CSS 留在文件里无害，standalone 本地调试仍可用；
   系统链路上被 Java 剥掉，等效零 CSS）。

> 存量脚本（q2_1 系列、eh、_q2_1_display）调用方式不变，零改动。

### 4.2 改2：`HtmlReportRenderer.scriptTableCss()` —— 单一真相源派生

REPORT_CSS 是报告页全量 CSS（shell/目录/body 白底/字号 !important），不能整份
进脚本片段（body 白底破坏前端 fragment iframe 透明设计）。在 HtmlReportRenderer
内新增 public 静态方法，紧挨 REPORT_CSS：

```java
/** 脚本 HTML 片段的表格皮肤: 以 REPORT_CSS 为唯一源派生, 禁止在 Python 维护第二份. */
public static String scriptTableCss() {
    // 1. #report → .unified-table 作用域替换;
    // 2. 显式保留清单: table/th/td/tr/斑马纹/hover/语义类/--report-* 变量;
    // 3. 剔除报告页专属: .report/.report-shell/#report-toc/body/html/全屏等;
    // 4. 追加 body{background:transparent} 对齐前端透明 iframe。
}
```

- 用**显式保留清单**派生而非黑名单删除：REPORT_CSS 增删表格规则时自动跟随，
  新增语义类只改一处；
- `.unified-table` 作用域保持 v-html 误注入零泄漏性质（页面无该元素不命中）。

### 4.3 改2 注入点：chat 最终回答边界（ChatStreamServiceImpl）

`ChatStreamServiceImpl:867` `resolveAndAppendCurrentResults(finalAnswer, refs)`
**之后、SSE 发送之前**，加一个纯函数后处理：

- 扫描最终回答文本中所有 ```html 围栏块 → 块首注入 `<style>` + `scriptTableCss()`
  （同一回答只注一次）；完整文档（`<!DOCTYPE` 开头）→ 注入其 `<head>`；
- **为什么是"扫描文本"而不是"hook 登记的 refs"**：hook 未启用的入口
  （`ENABLED_CTX_KEY` 没设）模型会复述 stdout，最终回答里同样有 ```html 块——
  扫描式注入对这两种来源一视同仁，**与 hooks 完全解耦**；
- **为什么不注进 registry 内容**：registry 登记的 renderable 内容会被
  SkillJobScheduler:527 的 resolve 复用进报告 markdown，注进去等于把 CSS
  重新带回长任务，前功尽弃。注入只发生在 chat 发送前的那一刻。

### 4.4 改3：报告侧（Skill Job / skill-flow）——零注入零改动

- 脚本 CSS 在改1 已剥净 → 报告 markdown 不再出现 `.unified-table` 样式块；
- 报告内所有表格（存量 markdown 表 + 脚本表）统一由 REPORT_CSS 的
  `#report table/th/td...` 上色 —— 单一真相源，**根治病根**；
- `renderMixedContent` 完整文档隔离 iframe：剥净后无样式 → 在 renderer 对
  "head 无 `<style>` 的完整文档 iframe" 注入 `scriptTableCss()` 兜底
  （完整文档模式在长任务里的展示路径）。

## 5. 双宿主仲裁（实施后的预期行为）

| 宿主 | 样式来源 | 结果 |
|---|---|---|
| chat 片段 iframe | SSE 边界注入 scriptTableCss | 与报告同源，红头白底/斑马纹/hover 一致 |
| chat 完整文档 srcdoc | SSE 边界注入 scriptTableCss | 同上 |
| 长任务 fragment 节点 | 仅 REPORT_CSS | **唯一真相源，零脚本 CSS，根治漂移** |
| 长任务完整文档 iframe | renderMixedContent 兜底注入 | REPORT_CSS 派生，风格一致 |
| v-html 误注入 | `.unified-table` 无命中元素 | 零泄漏（延续 §8.3 性质） |

个性化表格（`render_html_custom` 透传路径）：内联样式在报告被 REPORT_CSS 的
`!important` 压制；在 chat 被 scriptTableCss 的 `!important` 压制 ——
**两宿主终于同一份 CSS**，"统一赢" 语义双端成立。

### 5.1 对报告路径（#3/#4）的影响：减法而非加法

本方案对 1/2（chat）是换法（脚本自带 CSS 换成报告同源 CSS），对 3/4（报告）是
**减法**——删掉一份本不该在报告里的 CSS，全部收敛到 REPORT_CSS 单一来源：

| | 改前（现状） | 改后 |
|---|---|---|
| 报告 head 里的 CSS | REPORT_CSS **+ 提升进来的脚本 `.unified-table` 样式块**（java:1147-1157 hoist） | **只剩 REPORT_CSS**（脚本 `<style>` 在工具出口已被剥） |
| 存量 markdown 表 | 只被 REPORT_CSS 命中（脚本 CSS 是 `.unified-table` 作用域，命中不了它们） | **零变化**（仍只被 REPORT_CSS 命中） |
| 脚本表（q2_1/个性化） | 被**两份 CSS 竞争**：重叠属性 REPORT_CSS 赢，**不重叠属性（margin 10px、td color #333、hover 色、标题字号）被脚本 CSS 带偏**——即长任务里的漂移现象 | 只被 REPORT_CSS 命中，**与报告其他表完全一致** |

三个易担心点逐一排除：

1. **包壳不影响报告布局**：wrapper 是普通 `<div>`，报告 CSS 无任何规则指向
   `.unified-table`/`.table-wrap`（scriptTableCss 只注给 chat，不进报告），
   在报告里是透明空壳；REPORT_CSS 对报告内所有 `#report table` 生效，不依赖壳。
2. **语义类剥样式后仍生效**：`tr.summary-row` / `td.rowhead` / `td.red` 在
   REPORT_CSS 里有同名规则（个性化方案即按它对齐设计）；剥的只是 `<style>`
   文本，class 属性保留，汇总行灰底加粗照常。
3. **报告侧代码改动仅一处**：`renderMixedContent` 对"head 无 `<style>` 的完整
   文档 iframe"兜底注入 scriptTableCss（完整文档模式串进长任务的展示路径）；
   混排 fragment 节点、REPORT_CSS 本身、报告渲染主链路一行不动。

边界：若希望脚本表在报告里保留某些"漂移项"（如 margin 10px），正确做法是改
REPORT_CSS 本身——它从此是唯一改样式的地方。

## 6. 影响面

| 文件 | 改动 |
|---|---|
| `_html_table.py` | **零改动**（render_html_custom 已透传；render_html_table 不动，其 CSS 被 Java 剥掉等效失效） |
| 新增 `ScriptHtmlSanitizer.java` | 剥 `<style>`/`<script>` + 包壳 unified-table |
| `ScriptExecTool.java` | resultText 出口接 sanitizer |
| `HtmlReportRenderer.java` | +`scriptTableCss()`；renderMixedContent 无样式完整文档兜底注入 |
| `ChatStreamServiceImpl.java` | resolveAndAppendCurrentResults 之后注入 scriptTableCss |
| 前端 Markdown.vue | **零改动** |
| 存量脚本（q2_1 系列/eh/_q2_1_display） | **零改动** |
| 脚本调试页（ScriptDebugService / executeForDebug） | **不处理（拍板 2026-11-12）**：stdout 被剥后调试预览表格为裸表，接受——调试页只看数据结构，样式无关紧要 |
| 文档 | 前作三篇加修订注记；`_q2_1_display.py` docstring 提到的外壳职责改为 Java 层 |

回归风险点：fragment iframe 透明底（scriptTableCss 必须 body 透明）；
报告页字号 `#report :not(...)` !important 不进片段（保留清单要排除）；
sanitizer 不得误伤 ```html 围栏外的普通文本与下载块。

## 7. 验证清单

1. **Sanitizer 单测**：```html 块/完整文档的 `<style>`/`<script>` 被剥净；含 `<table>`
   的块被包 unified-table wrapper；下载块/json/markdown 不受影响；exit 信封保留；
2. **scriptTableCss 单测**：含 `rgb(192, 0, 0)`、`.unified-table` 前缀、斑马纹/hover/
   语义类；不含 `.report`/`#report-toc`/`body{background:#fff}`；与 REPORT_CSS 的
   表格段语义一致（改 REPORT_CSS 表格规则后派生自动跟随）；
3. **chat 注入**：最终回答含 ```html 块时块首出现一次 scriptTableCss；
   hook 禁用入口（不设 ENABLED_CTX_KEY）同样注入；完整文档 head 注入；
4. **chat 展示**：q2_1 fragment 与个性化表（eh 脚本）围栏渲染红头白底、汇总行灰底、
   iframe 透明底不泛白；
5. **长任务（核心验收）**：串入 q2_1 + 个性化表节点的长任务，汇总文档 head
   **无 `.unified-table` 样式块**，存量 markdown 表与注入前逐像素一致，
   脚本表由 REPORT_CSS 上色与报告其他表完全一致；
6. **skill-flow**：Python 节点（runScriptDirect 直连，无 hook）产出的完整文档
   隔离 iframe 有表格样式；混排节点无双重样式；
7. **回归**：/ai/chat 非 script 消息、echarts 块、下载链接均不受影响；
   脚本调试页 stdout 展示正常（表格裸表为拍板预期，不处理）。

## 8. 备选方案（否决理由存档）

| 方案 | 结论 |
|---|---|
| B. 前端拉取 CSS（`/report/table-css` 端点 + Markdown.vue wrapFragmentDoc 注入） | 单一真相源同样成立，但要动前端 + 新端点 + 完整文档模式还要前端 isFullHtmlDoc 分支再注一次；后端先上、前端旧版时 chat 表格白板，修复生效被前端发版节奏卡死 |
| C. 构建期从 Java 生成 Python CSS | 生成物是快照，Java 改完忘重建/忘 scp 容器漂移照旧；多一条生成+同步链路；且 Python 仍输出 `<style>`，长任务提升污染的病根没治 |
| D. 渲染器里识别脚本 `<style>` 丢弃 | skill-flow 直连路径不走该入口会漏网；且渲染器分不清"脚本的 style"和"AI 自产 style"（渲染器现特意保留 AI 样式，java:1026），全丢则 AI 表格退化白板，全留则污染依旧 |
| E. v1 的 ScriptHtmlStyleHook（PostActing 注入） | hook 依赖 ENABLED_CTX_KEY，runScriptDirect 等不走 hooks/LLM 的路径漏网；registry 内容被 SkillJobScheduler 复用进报告，注进 hook 会把 CSS 带回长任务 → 已废弃，改为工具出口剥 + 展示边界注 |

## 9. v1 → v2 变更记录

| 项 | v1 | v2（本文档） |
|---|---|---|
| Python 改动 | 剥 CSS、保留 wrapper | **零改动**（render_html_custom 透传即可，包壳移 Java） |
| 剥样式的位置 | 新 Hook（PostActing） | ScriptExecTool 出口（不依赖 hooks/LLM，四消费方必经） |
| chat 注入位置 | hook / SSE 边界（未定） | 定死 SSE 边界，**扫描文本**而非依赖 refs（hook 禁用也生效） |
| 包壳职责 | Python wrapper 保留 | Java sanitizer 包壳（render_html_custom 透传后 wrapper 缺失） |
