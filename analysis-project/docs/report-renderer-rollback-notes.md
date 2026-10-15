# 报告渲染修改回滚说明

## 回滚范围

本次准备回滚以下两类改动：

1. 汇总报告横向滚动条样式修改。
2. 汇总报告和 iframe 章节放大按钮移除或样式调整。

## 涉及文件

### `src/main/java/com/agentscopea2a/v2/skillManager/report/HtmlReportRenderer.java`

重点检查以下内容：

- `.table-scroll` 的 `overflow-x`、滚动条宽度和滑块颜色。
- `.table-scroll::-webkit-scrollbar` 相关规则。
- `.echarts-fullscreen` 和 `.html-fullscreen` 样式。
- `html-fullscreen` 按钮生成代码。
- `appendFramePart` 中对 iframe HTML 的按钮清理逻辑。
- 完整 HTML 表格自动包裹 `.table-scroll` 的逻辑。

### `frontend/src/api/skillFlow.ts`

重点检查：

- 汇总报告 Blob 生成时是否注入了额外的 `.table-scroll` 样式。
- 汇总报告 Blob 生成时是否注入了目录生成脚本。
- 前端是否覆盖了后端 `HtmlReportRenderer` 生成的报告样式。

### `frontend/src/components/ReportEditorDrawer.vue`

如只回滚汇总报告显示效果，编辑器外壳样式和目录布局逻辑不应一并回滚。需要单独确认：

- 编辑器 iframe 是否仍然保留目录。
- 编辑正文是否为目录预留空间。
- 编辑器是否继续使用后端返回的报告 CSS。

## 当前目标行为

- 横向表格可以滚动查看完整列内容。
- 表格不出现纵向滚动条。
- 汇总报告不显示额外的放大按钮。
- iframe 章节不重复显示放大按钮。
- 报告正文样式由 `HtmlReportRenderer.java` 统一生成。
- 前端不再注入第二套报告正文 CSS。

## 回滚后验证

回滚完成后建议检查：

1. 打开汇总报告，确认宽表右侧列和右边框可通过横向滚动查看。
2. 确认汇总页不显示放大按钮。
3. 确认 iframe 章节不显示重复放大按钮。
4. 确认左侧目录仍能滚动，正文不被目录遮挡。
5. 确认短章节 iframe 不保留固定 600px 空白。
