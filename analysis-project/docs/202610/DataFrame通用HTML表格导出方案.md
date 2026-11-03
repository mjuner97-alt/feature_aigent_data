# DataFrame 统一 HTML 表格样式方案

日期: 2026-10-30
状态: 方案（待确认后实施）

## 1. 需求

Skill 直接执行/脚本结果输出场景下，各脚本各自手拼 df 的 HTML 样式
（现状：`555153205/q2_1_metrics_by_dept_version_eh.py:170-180` 自带一整套内联 style），
风格不统一。要一个**通用函数**：

1. 入参 DataFrame，出参 HTML 表格代码（完整文档 `<!DOCTYPE html>` ... `</html>`）;
2. 统一样式：表头红色 rgb(192,0,0)，表格主体白色。

**不改 `_download.py`**（那是下载块协议，本需求是结果展示，不是文件下载）。

## 2. 前端渲染形态（约束）

`frontend/src/components/Markdown.vue` 对 done 文本的两条 HTML 路径：

| 形态 | 判定 | 渲染方式 |
|---|---|---|
| 完整 HTML 文档 | 全文剥掉 BOM/注释后以 `<!doctype`/`<html` 开头 (Markdown.vue:160) | iframe `srcdoc` 隔离渲染，自带样式生效 |
| html 围栏片段 | ```` ```html ```` 围栏 | isolate 模式包进沙箱 iframe，否则 v-html |

因此通用函数提供**两档输出**，同一套 CSS：

- `render_html_table(df)` → **完整文档**（默认满足需求原文）。脚本把它作为**独占输出**直接 print，
  前端自动走 iframe 渲染；不要放进 ```html 围栏、也不要和其他文本拼在同一次输出里，
  否则全文不再是 doctype 开头，走 v-html 会出现"文档套文档"。
- `render_html_table(df, fragment=True)` → **表格片段**（`<style>` + `<div>` + `<table>`），
  供需要"文字 + echarts 图 + 表格"混排的脚本放进 ```html 围栏用
  （q2_1_eh 这类既有结论又有图的场景）。

## 3. 交付物

新模块 `workspace/scripts/_html_table.py`（与 `_download.py`/`_sql_registry.py` 同级，
同款 import 方式：`from _html_table import render_html_table`）：

```python
def render_html_table(df, title=None, fragment=False):
    """df -> 统一样式 HTML. fragment=False 完整文档, True 返回片段(供 html 围栏)."""
```

- 无第三方依赖（pandas `to_html` + 固定模板），容器内可直接跑。
- `to_html(index=False, border=0, na_rep="")` 自带 HTML 转义，`|`/换行/`<>` 安全。

### 3.1 统一样式（唯一真相源，写在模块常量里）

```css
body  { font-family: "Microsoft YaHei", "PingFang SC", sans-serif; background:#fff; margin:16px; }
table { border-collapse: collapse; background:#ffffff; width:100%; }
th    { background: rgb(192, 0, 0); color:#fff; font-weight:600; padding:8px 12px; border:1px solid #c00000; text-align:center; }
td    { background:#ffffff; color:#333; padding:8px 12px; border:1px solid #d9d9d9; }
tr:nth-child(even) td { background:#fafafa; }          /* 斑马纹, 提升宽表可读性 */
td    { max-width:480px; word-break:break-all; }       /* 防超长文本撑破 */
```

- 必须 `<meta charset="utf-8">`，否则浏览器打开中文乱码。
- 外层 `overflow-x:auto` 包裹，窄屏横向滚动。

### 3.2 参数

| 参数 | 说明 |
|---|---|
| `df` | DataFrame；NaN 渲染为空单元格 |
| `title` | 可选标题，`<h3>` 渲染在表格上方 |
| `fragment` | False=完整文档（默认），True=片段 |

首列左对齐、合计行加粗这类**个性化诉求不做进通用函数**（那是风格再次发散的口子）；
q2_1_eh 的双行表头/合计行属定制，暂保留其现状，后续按需迁到本函数的单行表头形态。

## 4. 不做的事

- 不动 `_download.py`、不动 Java 侧、不动 Markdown.vue；
- 不做行内条件格式/色阶（下载 excel 已有 `print_download_xlsx`）；
- 不引 jinja2 / 外部 CSS / CDN。

## 5. 验证

1. 本机直接调：断言输出含 `<!DOCTYPE html>`、`rgb(192, 0, 0)`、`charset="utf-8"`；
   fragment 模式以 `<style>` 开头且不含 `<html>`。
2. 脏数据 df（NaN / `|` / 换行 / 超长文本 / 中文）渲染无错位。
3. 浏览器/前端预览：完整文档独占输出 → iframe 正常渲染；fragment 进 ```html 围栏 → 正常显示。
4. 内网 E2E：直接执行问数，结果表头红、表体白、风格与其他脚本一致。
