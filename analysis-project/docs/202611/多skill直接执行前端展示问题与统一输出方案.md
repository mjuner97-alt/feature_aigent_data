# 多 skill 直接执行前端展示问题与统一输出方案

> 2026/11/16 立项。前置：多执行后端已实施并验证（多skill命中全部直接执行方案.md +
> 双标签命中仅执行一个skill_内网问题分析与方案.md §1.1 G6 收窄），三个 skill 均正常执行。
> 本方案解决遗留的两个前端展示问题：
> ① 智能体活动仅展示最后一个工具的出入参（应有每一步的）；
> ② 最终结果仅展示一段（用户提议复用长任务"多 python 脚本统一输出"能力）。
> **拍板（2026/11/16）：问题②取方案A（围栏分段拼接）**；问题①修复无争议（整轮一次终态事件）；
> 单脚本路径不随修（维持现状，§3.3）。

---

## 1. 现象（2026/11/16 实测）

问句：`MMTA，FETA 风险情况，10月版本的质量，未闭环问题清单等`（命中 risk / zhengti / weibihuan 三个 skill，提及序 risk→zhengti→weibihuan）。

| 前端 | 接口 | 智能体活动 | 最终结果 |
| --- | --- | --- | --- |
| pm 调试页 `localhost:8081/pm/chat` | `/v2/ai/chat` | 3 条维度解析 + **仅 1 条** `✅ 完成：script_exec`（risk，带出入参），无 `🔧 调用工具` 行 | **仅【风险模型】一段**（risk 的 HTML，且显示为转义源码文本） |
| 本机 Vue `localhost:8081/chat` | `/ai/chat` | 类似 pm | **仅一段** |
| 内网前端（类似 /chat） | `/ai/chat` | 3 对「已匹配技能 + 工具执行完成」**齐全** | **仅最后一个** |

## 2. 根因

### 2.1 共根因：多执行循环把「每段一次 sendText(…, true)」当终态事件发

`TextPayload.chunk(content, true)` 在两条路径上都是**整轮终态**语义，多执行循环却对每段结果各调一次：

- **v2**（`V2ChatStreamServiceImpl.runDirectExecutionMulti`，:721/728/737）：`managerStrategy.sendText` 把每个 TextPayload 都发成 SSE **`done` 事件**（V2ChatStreamServiceImpl.java:258-274，lineResult=resultAll=content）。
  pm 侧 `api/chat.ts` 将 `done` 归一化为 `{type:'done'}`，`ChatPanel.tsx` 的 done 处理器（:423-448）**用 fullText 替换回复文本并 `break` 退出事件循环**。
  → 第一个 skill 执行完（risk）即终止消费：skill 2/3 的 `tool_call_start`/`tool_result_end` 全部被丢弃（活动缺行），回复被替换为第一段。
- **v1**（`ChatStreamServiceImpl.runDirectExecutionMulti`，:740/748/758）：`sendText` 发 `TextManagerResponseDto` 且 **`finish=true`**（ChatStreamServiceImpl.java:267-281）。
  Vue 侧 `ChatPanel.vue` text 处理器（:237-241）append 后 **`if (evt.finish) break`**，同样提前终止。
- **内网前端**（类似 /chat，非本仓库代码）：从「活动 3 对齐全展示」推断其不因 finish 中断消费，但正文按 finish 做替换/覆盖 → 只留最后一个。
- **混排轮反问（拍板①方案B）同样中招**：ask plan 的反问文本在循环后逐条 `sendText(askPrompt, true)`（v2 :747-751 / v1 :768-772）——它们也是终态事件；实际由于前端早已 break，反问从未到达前端。

现象与代码逐项对上：pm trace 里「维度解析 3 条（循环前发）+ 仅 1 条完成 + 无 🔧 行」= 第一个 skill 的 start/end 被 ActivityFeed 按 toolCallId 合并成一行（ChatPanel.tsx:330-341），后续事件因 break 全丢；回复 = done 替换后的第一段。

### 2.2 pm 前端对原始 HTML 的显示缺陷（问题②的另一面）

- pm `Markdown.tsx` 只认 **` ```html ` 围栏块**（parseBlocks :39-56）：围栏块 → `HtmlBlock` → 完整文档进 `sandbox="allow-scripts"` 的 srcdoc iframe（:82-90）；**非围栏的原始 HTML 走 markdownToHtml → escapeHtml → 显示为转义源码文本**（用户粘贴中 `\"zh-CN` 的来源）。
- 因此 pm 上**连单脚本直接执行的结果（raw 完整文档）今天也是源码文本**——不只多执行轮有问题。
- Vue `Markdown.vue` 两条都通：整段文本是完整文档 → iframe srcdoc 隔离渲染（isFullHtmlDoc :154-161）；` ```html ` 围栏 → inline v-html 渲染（renderMarkdownSegment :243-247）；原始 HTML 片段 → v-html（escapePreservingHtml :330-342 保留标签）。
- 内网前端与 /chat 类似（单脚本表格可正常显示可证）。

## 3. 修复设计

### 3.1 问题①：整轮一次终态事件（后端必做，前端零改动）

`runDirectExecutionMulti`（v1 与 v2 **同步改**，内网 /ai/chat 走 v1）：

```
循环内：
    think 进度「已匹配技能…」            ← 保留（实时反馈）
    tool_call_start → 执行 → tool_result_end   ← 保留（活动行出入参的来源）
    【删除】sendText(标题+stdout, true)
    【删除】sendText(报错文案, true)      ← 失败文案仍累积 answerContent
    answerContent 累积照旧
循环后：
    ask 反问文本追加进 answerContent（不再单独 sendText）
    sendThink(done)
    sendText(完整 answerContent, true)   ← 整轮唯一一次终态
```

修复后事件时序：`3×dimension_extract → [think 已匹配 → tool_call_start → tool_result_end] × N → think done → 1×text/done(完整)`。

效果：
- 活动区 N 个 skill 各一行（start/end 按 toolCallId 合并），**每行带独立入参/出参折叠面板**——问题①诉求；
- 回复 = 完整拼接结果——问题②的"内容完整性"部分；
- 混排轮反问随最终文本一起到达。

pm 的 done 处理器用 fullText 整体替换回复（:439），一次 done 带全文正好匹配；Vue text 处理器 append 一次即 break，全文一次到达也匹配。前端无需改动。

### 3.2 问题②：最终结果统一输出（拍板选型）

**方案A（2026/11/16 拍板选定）**
最终 answer = 逐段 `【short_summary】标题 + ` ```html ` 围栏包裹的该段 stdout`，段间用 `<br><br>` 分隔；失败段为标题 + 报错文案（纯文本）。
段分隔格式（2026/11/17 定稿，内网前端实测）：`\n\n` 空行与 `###` 标题在内网前端均不渲染（粘连不可分），`<br>` 生效 → 标题行格式定为 `"<br><br>" + title + "\n"`，**首段不加 `<br><br>`**（`answerContent.length() > 0` 判断，success/失败/异常三分支统一），后续段每段前加。

段标题来源（现有实现，无需改动）：`DirectExecutionPlan.displayTitle()` ← `DirectSkillExecutor.displayTitleOf(meta)`（DirectSkillExecutor.java:632-637）= `"【" + skill_routing_metadata.short_summary + "】"`，即**直接取对应 skill 配置的 short_summary**（配置广场维护）；空/blank 回退 skill 英文名。metadata 行未配置时 Repository 层另有 description 兜底（SkillRoutingMetadataRepository.java:365）；内置 skill 的 short_summary 由 BuiltinSkillRegistrar 从 SKILL.md frontmatter description 自动生成。

```markdown
【风险模型】

```html
<!DOCTYPE html>…
```

【文书质量缺陷密度】

```html
<!DOCTYPE html>…
```
```

- 渲染兼容矩阵：pm 围栏块 → 每段独立沙箱 iframe（样式/脚本隔离，即"统一输出"效果）；Vue /chat 围栏块 → inline v-html 渲染；内网前端同 /chat。**三端都把每段渲染为可读表格而非源码**。
- 与 LLM 路径约定一致（脚本输出本就以 ```html 围栏为渲染契约，ChatScriptExecResultHook 同款协议），实现只是「标题+围栏」的字符串拼接，无新依赖。
- 已知限制：pm 沙箱 iframe 内的下载短链点击受 sandbox 限制（无 allow-top-navigation/allow-popups）；Vue/内网 inline 渲染不受影响。

**与 个性化HTML表格兼容统一样式方案.md 的兼容性（2026/11/16 核对，不冲突）**：

| 核对项 | 结论 |
|---|---|
| 脚本侧 `render_html_table` / `render_html_custom`（统一样式方案 §3-§5） | 零影响——方案A 不改脚本输出形态 |
| ScriptExecTool 出口 v3 包壳（剥 script/style + `.unified-table` 作用域 + 兜底皮肤） | 零影响——围栏 helper 加在流服务层（runDirectExecution(Multi) 出口），ScriptExecTool 不动 |
| Vue/内网 inline v-html 渲染安全性 | **依赖 `.unified-table` class 作用域**（统一样式方案 §8.3 + 2026-11-09 命名空间修订）：完整文档被 v-html 注入页面时 `<style>` 生效于页面，但规则全部挂 class 作用域 → 只命中 unified-table 元素、零泄漏。§8 "使用约束放宽（作用域兜底，完整文档独占不再是唯一防线）"本就是为混排场景设计，方案A 是其自然延伸 |
| `!important` "统一赢"（§8 修订 1） | iframe / inline 两种渲染下同样生效，Tier 0 零改造兼容不受影响 |
| 透明背景 + `width:max-content` 收缩（§8 修订 5） | Vue inline 渲染透出聊天底色、表格收缩成左侧白色卡片——正是该修订的目标形态；pm iframe 白底正常 |
| 两档输出约束（§5：完整文档独占→iframe；fragment→围栏混排） | 方案A 把**完整文档**（ScriptExecTool v3 包壳产物）放进围栏，Vue/内网解析时 html/head/body 壳被剥、style/正文保留 → 依赖作用域兜底，符合 §8 放宽后的约束 |

**新增注意点（围栏 helper 必做门控）**：helper 须按"stdout 是否 HTML"判定后才围栏——以 `<!doctype`/`<html` 开头或含 `<table`/`<style`（对齐 HtmlReportRenderer 的 `COMPLETE_HTML_START` 形态）才包 ` ```html `；纯文本输出（报错文案、下载块说明文字、脚本 plain stdout）保持原样走 markdown，否则会掉进前端 html 块渲染丢掉 markdown 排版。

**方案B（不采用，留档）：复用长任务 HtmlReportRenderer 统一渲染**
循环结束后把「Markdown 标题 + 各段完整 HTML 文档」混排文本交 `LongTaskReportRenderer.renderWithoutFullscreen(text, title)`（复用 HtmlReportRenderer.renderMixedContent：每份完整文档转独立 srcdoc iframe、样式脚本隔离、可选 TOC/echarts 归一化）产出**一份自包含统一文档**，再按方案A的围栏格式发送（pm 需围栏才渲染）。
- 优点：真正复用长任务统一输出的完整能力（目录、统一样式、echarts 归一化、报告级观感）。
- 缺点：pm 侧形成 iframe 套 iframe（统一文档 srcdoc 内再嵌每段 srcdoc），重；外层 REPORT_CSS 的 TOC/全屏脚本在聊天气泡里意义有限； echarts.min.js 按文档内联体积大。内网前端对"整段完整文档 iframe"已支持，但嵌套 iframe 未验证。

**方案C（不采用，留档兜底）：长任务式落盘 + 链接**
统一报告走 FlowReportStorage 落盘（同长任务），聊天正文发 Markdown 链接。零渲染风险、三端必然可用，但表格不再内联展示，与现状单脚本内联体验倒退；仅当 A 在内网前端验证不通过时回退采用。

**拍板（2026/11/16）：问题②取方案A**；B/C 留档。单脚本对齐（§3.3）已拍板**不随修**（维持现状）。

### 3.3 单脚本路径对齐（2026/11/16 拍板：不随修，维持现状）

单脚本 `runDirectExecution` 末尾同样 `sendText(stdout, true)`（v2 :619 / v1 :664）发 raw 完整文档——pm 上显示为源码文本的问题**维持现状，不在本次范围**。理由：单脚本围栏化是展示行为变化（Vue/内网从"整文档 iframe"变"inline v-html"，pm 从源码文本变 iframe），需三类前端各回归，收益/风险比不划算；后续如需对齐可复用本次的围栏 helper（改动点即 §4-④ 两处出口）。

## 4. 改动清单

| # | 文件 | 改动 |
|---|---|---|
| ① | `V2ChatStreamServiceImpl.runDirectExecutionMulti`（:667-772） | 删循环内/反问的 sendText；反问追加 answerContent；循环后整轮一次 `sendText(answerContent, true)` |
| ② | `ChatStreamServiceImpl.runDirectExecutionMulti`（:700-775） | 同①（v1 内网路径必须同步） |
| ③ | 段围栏 helper（方案A）：`fenceStdout(stdout)`——stdout 里的每个完整 HTML 文档（doctype/html 形态）各自包 ```html 围栏；文档间纯文本（下载块 📥 前缀/失败文案/plain stdout）保持 markdown 原样；已含 ``` 的输出原样返回防嵌套围栏；多执行路径使用（单脚本拍板不随修） | 拼接格式统一出口；实施时从"整体门控"细化为"逐完整文档拆分围栏"——stdout 可为混合形态（下载块协议把链接原位替换成独立完整 HTML 文档，与表格文档共存于同段 stdout，ScriptExecTool.java:935-936），整体包会吞掉文档间 markdown 文本 |
| ④ | ~~单脚本 `runDirectExecution`（v2 :619 / v1 :664）~~ | **不改**（2026/11/16 拍板维持现状，§3.3） |
| ⑤ | （仅方案B）两服务注入 `LongTaskReportRenderer`，循环后统一渲染再围栏发送 | — |
| ⑥ | （可选增强）pm `Markdown.tsx` 加完整文档自动检测（对齐 Vue isFullHtmlDoc），raw 文档不再显示源码 | 防御性：非本方案路径漏围栏时兜底 |

前端功能行为无必须改动（⑥ 可选）。

## 5. 影响面核对

| 既有约定 | 影响 |
|---|---|
| 内网反问轮契约（纯一次性 text 事件，零 think/done） | 纯反问轮（无执行）路径不动；混排轮修复后为「工具事件 + 单条终态 text」，与契约形状一致，内网 E2E 复验 |
| pm done 处理器（fullText 整体替换 + break） | 单次终态正好匹配；多执行不再触发提前 break |
| Vue text 处理器（append + finish break） | 同上 |
| SSE 前端 90s stall watchdog（pm，ChatPanel.tsx:259-280） | 每 skill 都有 think+tool 事件重置计时，串行多脚本不会误报 |
| 快照 recordResult / recordLastExec（拍板⑤记最后执行） | 不动 |
| 提及序 / 缺参混排 / 失败继续（拍板②③④） | 不动；仅终态事件从 N 次归 1 次 |
| 统一表格样式（.unified-table class 作用域） | 方案A 下 Vue inline 渲染同页多段样式重复但规则相同，无冲突；pm 走独立 iframe 天然隔离。方案A 依赖该作用域防泄漏（见 §3.2 兼容性表）；`!important` 统一赢 / 透明收缩语义在两种渲染下均保持 |
| `/ai/chat` 与 `/v2/ai/chat` 双路径一致性 | ①② 成对改 |

## 6. 测试计划

- 本机 E2E（pm `/v2/ai/chat`，三 skill 问句）：
  - 活动区 = 3 条维度解析 + 3 行 script_exec（每行入参/出参折叠面板齐全、首行 🔧→✅ 合并语义不变）；
  - 回复 = 3 段标题 + 3 个可渲染表格（非源码文本）；
  - 失败轮：人为停用一个脚本容器/改坏 scriptId → 失败段报错文案 + 其余段正常；
  - 混排反问轮：缺参 skill → 全部结果后同轮反问文本可见。
- 本机 E2E（Vue `/ai/chat`）：同上场景，正文 3 段 inline 渲染。
- 统一样式渲染回归（方案A × 个性化HTML表格兼容统一样式方案）：围栏段在 pm iframe / Vue inline 下红表头 + 斑马纹生效；个性化脚本（render_html_custom 产物）汇总行语义类样式保留；页面无样式泄漏（聊天页非表格区域不受 `.unified-table` CSS 影响）；表格贴内容收缩、无右侧白底空洞（§8.5）。
- 单测：现有 917 不受影响（执行编排未动）；`DirectExecutionPlan`/标题逻辑无变化。
- 内网 E2E：内网前端复验活动 3 对 + 正文 3 段 + 表格内短链可点击（inline 渲染路径）。
- 单脚本回归：单 skill 问句本机 pm/Vue 各验一次，确认行为不变（拍板不随修，防误伤）。

## 7. 实施顺序

1. ~~拍板问题②选型 + 单脚本是否随修~~（均已拍板：方案A；单脚本不随修维持现状，2026/11/16）。
2. ~~①② 后端终态事件修复 + ③ 围栏 helper~~（2026/11/16 已实施：两服务 `runDirectExecutionMulti` 循环内仅 think+tool 事件、反问并入 answerContent、循环后单次终态；`fenceStdout` 按逐完整 HTML 文档拆分围栏。编译 + 917 单测过）→ 本机 pm/Vue E2E。
3. 内网部署 + 内网前端 E2E（混排反问契约一并确认——多skill命中全部直接执行方案.md §7.1 的前置条件）。
