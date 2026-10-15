# weekly_demo 本地演示流程

运行 `py scripts/weekly_demo_flow.py demo` 会在 `tmp/weekly-demo/` 中注册模板、生成演示数据、执行 `report_template:weekly_demo` 并校验报告。

- `seed`：只创建注册表和演示数据
- `exec`：执行模板并生成 Markdown、HTML、JSON 结果
- `verify`：检查结果状态和四个报告章节
- `reset`：删除演示目录
- `demo`：串联完整流程

缺失或停用模板会返回 `ReportTemplateNotFoundOrDisabled`。所有文件均为本地演示数据，不连接真实业务库。
