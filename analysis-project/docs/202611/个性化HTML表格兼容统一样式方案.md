# 个性化 HTML 表格兼容统一样式方案

日期: 2026-11-03
状态: 已实施（2026-11-03，本机验证过；内网 E2E 待验）
前作: `docs/202610/DataFrame通用HTML表格导出方案.md`（`_html_table.py` 的来历）

实施落地:
- `_html_table.py`: 新增 `render_html_custom(body_html, title=None, fragment=False)` +
  语义类 `tr.summary-row` / `td.rowhead`；文档/片段组装抽为内部 `_assemble`；
- `555153205/_q2_1_display.py`: 新增，`format_df_for_html` Tier 1 版
  （`DIMENSION_CN_MAP` 支持传参，隐藏列清单默认 `HIDDEN_COLS`）；
- `555153205/q2_1_metrics_by_dept_version_eh.py`: 双行表头手拼内联样式迁移为
  语义类 + `render_html_custom(fragment=True)`（§4.1 的活案例）。

## 1. 背景

`_html_table.render_html_table(df)` 已统一"表头红 / 表体白"的基础表格皮肤，
`q2_1_metrics_by_dept_version.py:136` 等脚本走 `print(render_html_table(show_df))`。

现在出现个性化诉求（如 `format_df_for_html()`，规划中）：

1. `dim_xxx` 列名转中文、隐藏原子指标列、百分比格式化 —— **数据变换**；
2. 汇总行（维度列值 == '汇总'）加粗 + 灰底 —— **行级条件样式**；
3. 自带一整套 `<th>/<td>` 内联 style（灰底表头、`#ddd` 边框）—— **表格外壳样式**。

前作 §3.2 明确"合计行加粗这类个性化诉求不做进通用函数"，但没给个性化脚本留出口，
脚本作者只能整段手拼内联样式 → 风格再次发散（灰表头 vs 红表头并存）。

## 2. 兼容原则（目标）

1. **`render_html_table(df)` 保持不变** —— 仅传 df 的通用用法零感知；
2. **新增"接收现成 HTML"的包装函数** —— 个性化的表格 HTML 原样保留，
   外面套 `render_html_table` 已有的统一外壳（统一 CSS + `.table-wrap` 滚动容器 +
   完整文档/片段两档输出），即 **统一外壳 + 个性化表体**；
3. 数据变换逻辑（重命名/隐藏列/百分比/汇总行判定）**留在个性化函数里**，
   `_html_table.py` 不吸收任何业务逻辑。

## 3. API 设计

`_html_table.py` 新增一个函数，复用现有 `CSS` / `DOC_TEMPLATE` / `.table-wrap`：

```python
def render_html_custom(body_html, title=None, fragment=False):
    """现成 HTML 表体 -> 统一外壳输出.

    body_html: 脚本自己生成的表格 HTML (含 <table> 或任意块级内容, 信任脚本输出, 不转义);
    fragment=False: 完整文档 (<!DOCTYPE html>), 独占 print, 前端走 iframe;
    fragment=True:  <style>+<div class="table-wrap"> 片段, 供 ```html 围栏混排.
    """
```

输出结构与 `render_html_table` 完全同构：

```
完整文档: DOC_TEMPLATE(title, css=CSS, body=<div class="table-wrap">{title}{body_html}</div>)
片段:     <style>{CSS}</style>\n<div class="table-wrap">{title}{body_html}</div>
```

**关键收益**：统一 CSS 用的是裸选择器 `table/th/td`，包装后个性化表格自动获得
字体、斑马纹、hover 高亮、`.table-wrap` 横向滚动 —— 即使一行不改也能受益。

### 3.1 两级兼容（重要）

| 级别 | 改造量 | 效果 |
|---|---|---|
| **Tier 0 零改造兼容** | 个性化函数一行不改，输出直接丢给 `render_html_custom` | 内联 style 的属性（灰表头/边框/padding）保留个性；没写的属性（字体/斑马纹/hover/滚动容器/文档外壳）自动统一。CSS 级联规则：inline style > 任何选择器 |
| **Tier 1 推荐改造** | 删掉 `<table>/<th>/<td>` 上的内联 style，只留语义类（见 §4） | 外观完全统一（红表头），汇总行/标红通过语义类表达，纳入统一样式的唯一真相源 |

Tier 0 是"存量脚本无痛接入"的兜底；Tier 1 是目标形态。两级都合法，不做强制。

## 4. 个性化函数改造规范（Tier 1）

`format_df_for_html` 三层拆分：

1. **数据变换（保留不动）**：`dim_` 重命名、隐藏列、`fillna("-")`、百分比格式化；
2. **行级条件样式 → 语义类**：汇总行 `is_summary` 判定保留，
   输出 `<tr class="summary-row">` 而非 `<tr style="font-weight:bold;...">`；
   单元格强调用 `<td class="red">`（统一 CSS 已有）；
3. **外壳内联 style（全部删除）**：`<table>/<th>/<td>` 上的
   `style='background-color... border... padding...'` 交给统一 CSS。

统一 CSS 追加一个语义扩展点（放在斑马纹规则之后，特异性 `tr.summary-row td`
(0,1,2) > `tr:nth-child(even) td` (0,1,1)，汇总行不被斑马纹盖掉）：

```css
tr.summary-row td { font-weight:600; background:#f0f0f0; }
```

后续任何脚本的行级个性化都走这个模式：**申请语义类 → 统一 CSS 落样式**，
避免 inline style 散落各脚本。

### 4.1 转义责任变化（必须做）

`render_html_table` 走 `pandas.to_html(escape=True)`，转义是自动的；
`render_html_custom` 收到的 `body_html` 是信任输入，**不转义**。
因此个性化函数拼接单元格值时必须自己转义（现有 `format_df_for_html` 草稿没做）：

```python
from html import escape
html += f"<td>{escape(str(val))}</td>"
```

脏数据（`<`/`&`/`|`）会直接打穿 HTML 结构，这条是 Tier 1 改造的硬性项。

### 4.2 草稿中顺带修的点

- `"<p>无数据</p >"` → `"<p>无数据</p>"`（多余空格）；
- `DIMENSION_CN_MAP` 反查维度列的注释摇摆（"简单起见/更稳健的方法"）——
  定稿：重命名前先记 `dim_cols_display = {DIMENSION_CN_MAP.get(c[4:], c) for c in df.columns if c.startswith("dim_")}`，
  不靠"含中文猜维度列"。

## 5. 落地示例（q2_1）

```python
from _html_table import render_html_custom
from _q2_1_display import format_df_for_html   # 或仍在脚本内定义

show_df = build_show_df(...)                   # 原数据变换逻辑
print(render_html_custom(format_df_for_html(show_df), title="Q2-1 部门版本汇总"))
```

- 独占输出 → 完整文档 → 前端 iframe 渲染；
- 若要与 echarts 混排：`render_html_custom(..., fragment=True)` 进 ```html 围栏，
  与 `render_html_table` 的两档约束一致（前作 §2）。

## 6. 不做的事

- 不动 `render_html_table`、`_download.py`、Java 侧、Markdown.vue；
- 不把 `format_df_for_html` 的业务逻辑（隐藏列清单、百分比列判定）搬进 `_html_table.py`；
- 不做 CSS 隔离/命名空间（`table` 裸选择器本就是对齐项；同一文档混排多张表
  （通用+个性化）时风格天然一致，属预期行为）；
- 不引 jinja2 / bs4 / 外部 CSS。

## 7. 验证清单

1. **通用回归**：`render_html_table(df)` 输出与改造前 diff 为零（函数体未动，冒烟即可）；
2. **Tier 0**：未改造的个性化 HTML 丢进 `render_html_custom`，
   完整文档以 `<!DOCTYPE html>` 开头、含 `rgb(192, 0, 0)`（如果表体没内联盖掉表头）；
   fragment 以 `<style>` 开头不含 `<html>`；
3. **Tier 1**：改造后汇总行加粗灰底且不被斑马纹覆盖（第 2/4 行的汇总行仍 `#f0f0f0`）；
   表头红色、td 居中、边框 `#bfbfbf`；
4. **转义**：单元格值含 `<`、`&`、换行不破坏结构；
5. **前端**：完整文档独占 print → iframe 正常；fragment 围栏混排正常；
6. **内网 E2E**：直接执行 q2_1，输出表与通用表格并排风格一致，汇总行有强调。

## 8. v2 修订（2026-11-03 内网踩坑后，对齐 HtmlReportRenderer）

内网 E2E 发现两个问题，参照 `com/agentscopea2a/v2/skillManager/report/HtmlReportRenderer.java`
的 REPORT_CSS 设计修订：

**现象 1**：个性化 HTML（th 带 `style="background-color:#f2f2f2"` 内联样式）包进
`render_html_custom` 后红头不生效。
原因：inline style 优先级高于任何普通选择器，原 §3.1 Tier 0 "内联赢" 的语义
不满足"统一优先"诉求。

**现象 2**：表格右侧出现大块空白。
原因：完整文档与其他输出混排时，整条消息不以 doctype 开头，前端掉进 v-html 路径，
统一 `<style>`（`body{margin:16px}`、`*{box-sizing}` 等）泄漏进聊天页面全局污染布局。

**修订**（对齐 REPORT_CSS 的两个核心手法）：

1. **外壳属性全部 `!important`**：th 背景/字色/加粗/padding/border/对齐、
   td border/padding/对齐、table 折叠/宽度、斑马纹、hover、语义类全部加 `!important`。
   CSS 规范里 `!important` 声明压制普通 inline style → 个性化 HTML **零改造**
   也会被强制统一（红头直接生效），Tier 0 语义从"内联赢"改为"统一赢"。
2. **`td color` 豁免**（同 HtmlReportRenderer `#report td { color:#000 }` 不加 important）：
   业务数据标红等非受管个性保留。
3. **全部规则挂 `#report` 作用域 + 输出包 `<div id="report">`**：两档输出（完整文档/片段）
   正文都包在 `<div id="report">` 里，CSS 选择器全部 `#report ` 前缀；
   整份 HTML 被误注入其他页面（v-html）时选择器不命中任何元素 → 样式零泄漏（修现象 2）。
   原全局规则 `*{box-sizing}`、`body{margin}` 删除，box-sizing 收进 `#report, #report *`。
4. **th 去 nowrap**，td 改 `word-break:break-word + overflow-wrap:anywhere`
   （对齐 REPORT_CSS 的 `white-space:normal` + `overflow-wrap:anywhere`），
   宽表不横向撑破，滚动容器兜底保留。
5. **背景透明 + 表格贴内容收缩**（内网二次反馈）：前端 fragment iframe 是透明设计
   （Markdown.vue `.fragment-frame` + `wrapFragmentDoc` 的 `background:transparent`），
   统一样式给 `#report` 画白底会在表格右侧显出大片白色空白区。修订：
   `#report` 去掉 `background:#fff`（透出前端主题底色）；
   `table` 从 `width:100%` 改为 REPORT_CSS 同款
   `width:max-content !important; min-width:0 !important; max-width:100% !important`
   （"按内容宽度显示，不强行铺满页面"），白色只留在实际单元格区域，表格收缩成
   左侧白色卡片，右侧透出聊天底色。单元格白底保留保证可读性。

**使用约束放宽**：完整文档独占输出仍是最佳实践，但不再是防泄漏的唯一防线
（`#report` 作用域兜底）；fragment 混排依旧推荐。

**Tier 1 语义类仍是推荐形态**：内联样式的汇总行（`style="font-weight:bold;background-color:#f9f9f9"`)
会被斑马纹 `!important` 盖掉底色只留加粗；改用 `tr class="summary-row"` 才有稳定的
加粗灰底强调。转义要求（§4.1）不变。

> **⚠️ §8.3 作用域已被修订（2026-11-09）**：`#report` 命名空间与长任务
> HtmlReportRenderer 的容器 `<div id="report">` 撞名，片段串进长任务后样式全局污染
> 存量表格。已改为独有 class `unified-table` 作用域（Python 单点改 `_html_table.py`），
> 详见 `docs/202611/统一样式片段长任务样式污染修复方案.md`。
