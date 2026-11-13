# 统一样式 #report 重写双重前缀 bug 修复方案

日期: 2026-11-13
状态: 已实施（2026-11-13，ScriptHtmlSanitizerTest 12 测试全过，全量回归待确认；内网 E2E 待验）
前作: `docs/202611/脚本表格样式上移hooks层统一方案.md`（§10 v3 修订记录）

## 1. 现象（内网 2026-11-13）

wenshu_chadui_all 插队统计脚本输出完整文档表格，前端无默认统一样式
（无 rgb(192,0,0) 红表头/白底/斑马纹）。内网 stdout 原文显示脚本 CSS
（旧版 `#report` 作用域版，含 padding:16px / th padding:8px 14px）经
sanitizer 重写后选择器全部变成**双重前缀**：

```
.unified-table .unified-table{ box-sizing: border-box; }
.unified-table .unified-table th{ background: rgb(192, 0, 0) !important; ... }
.unified-table .unified-table td{ ... }
```

页面 DOM 只有一层 `<div class="unified-table">` 壳 → `.unified-table .unified-table th`
匹配不到任何元素 → 整份皮肤失效，表格裸奔。

## 2. 根因：`rewriteOne` 的 #report 替换在加前缀之前，且替换后再无条件加前缀

`ScriptHtmlSanitizer.rewriteOne`（v3 现版）执行顺序：

```java
if (lower.contains("unified-table")) return sel;   // 已 scope 跳过 —— 但此时 selector 还是 "#report"
...
sel = sel.replaceAll("(?i)#report\\b", ".unified-table");   // ← 替换成 ".unified-table"
return ".unified-table " + sel;                              // ← 又无条件加前缀 → 双重
```

`#report th` 这类选择器进入时**不含** `unified-table` 文本（幂等守卫拦不住），
先被替换成 `.unified-table th`，随后再被整体加前缀 → `.unified-table .unified-table th`。

受影响的选择器形态（旧版 _html_table.py CSS 全部中招）：

| 原始 | 错误输出 | 正确输出 |
|---|---|---|
| `#report` | `.unified-table .unified-table` | `.unified-table` |
| `#report th` | `.unified-table .unified-table th` | `.unified-table th` |
| `#report .table-wrap` | `.unified-table .unified-table .table-wrap` | `.unified-table .table-wrap` |
| `#report td.red, #report .red` | 两个都双重 | `.unified-table td.red, .unified-table .red` |

## 3. 为什么本地 10 个单测没抓住

`fencedBlockStyleScopedAndWrapped` 等测试断言用的是 **substring 包含**：
`assertTrue(out.contains(".unified-table th"))` —— 而
`.unified-table .unified-table th` **本身就是它的子串**，断言恒真。
`assertFalse(out.contains("#report"))` 也只证明替换发生了，没证明只发生一次。

教训：作用域重写类断言必须用**精确计数/反双重前缀断言**，
子串包含断言对"重复前缀"类 bug 天然失效。

## 4. 修复

`ScriptHtmlSanitizer.rewriteOne` 两处：

1. **#report 替换后判重再加前缀**：替换产物已含 `unified-table` 时直接返回，
   不再加前缀；
2. **双重前缀塌缩归一**（自愈存量污染）：`(?i)(\.unified-table[\s>+~]+)+\.unified-table`
   → `.unified-table`，内网已产出的 `.unified-table .unified-table th` 类历史
   脏数据再过一遍 sanitizer 即可修复（幂等性同时得到加强）。

修复后执行序：

```java
String rewritten = sel.replaceAll("(?i)#report\\b", ".unified-table");
rewritten = DOUBLED_SCOPE.matcher(rewritten).replaceAll(".unified-table");  // 塌缩
return rewritten.toLowerCase().contains("unified-table")
        ? rewritten : ".unified-table " + rewritten;                        // 判重
```

`@规则`/keyframes 步进/ROOTISH 分支顺序不变。

## 5. 测试补充（ScriptHtmlSanitizerTest）

| 用例 | 断言 |
|---|---|
| fencedBlockStyleScopedAndWrapped（加强） | `assertFalse(out.contains(".unified-table .unified-table"))` |
| pythonLegacyIdReportWrapperNormalized（加强） | 同上 + `assertEquals(1, count(out, ".unified-table th"))` |
| 新增 doubleScopeSelectorSelfHeal | 输入已是双重前缀的 CSS（模拟内网存量 stdout 再过一遍）→ 输出单前缀、二次 sanitize 幂等 |
| 新增 reportSelectorExactRewrite | `#report` / `#report .table-wrap` / `#report td.red, #report .red` 精确等于预期单前缀形态 |

## 6. 影响面

- **只改** `ScriptHtmlSanitizer.rewriteOne` + 测试；HTML 结构、包壳、皮肤注入、
  script 剥除逻辑全部不动；`HtmlReportRenderer` 继续一行不动；
- 存量脚本零改动（旧 `#report` 版 CSS 由替换+判重正确收敛为单前缀；
  新 unified-table 版 CSS 由幂等守卫直接跳过）；
- 内网已产出的脏 stdout 无需手工处理——sanitizer 幂等，同一条消息重放/
  下次执行即自愈；
- 全量单测回归（当前 896 全过，修复后须仍全过）。
