# weekly_demo 本地报告模板演示实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 在项目内提供隔离的 `weekly_demo` 模板注册、执行、渲染、验证和重置闭环。

**Architecture:** 使用 Python 标准库实现本地 JSON 注册表和渲染器，演示数据与输出放在 `tmp/weekly-demo/`。命令行入口按 `seed|exec|verify|reset|demo` 操作，`demo` 串联完整流程。

**Tech Stack:** Python 3 标准库、JSON、Markdown/HTML 字符串模板。

## Global Constraints

- 演示数据仅写入项目 `tmp/weekly-demo/`。
- 模板编码固定为 `weekly_demo`，调用 ID 固定为 `report_template:weekly_demo`。
- 缺失或停用模板返回 `ReportTemplateNotFoundOrDisabled`。
- 不连接真实数据库，不改变生产注册表。

### Task 1: 本地模板执行器

**Files:**
- Create: `scripts/weekly_demo_flow.py`
- Test: `scripts/test_weekly_demo_flow.py`

- [ ] 编写测试覆盖 seed、exec、五章节输出和缺失模板错误。
- [ ] 实现 `seed(root)`, `execute(root, script_id)`, `verify(root)`, `reset(root)` 以及 argparse 入口。
- [ ] `execute` 写出 `weekly-report.md`、`weekly-report.html`、`execution-result.json`。
- [ ] 运行 `python -m unittest scripts/test_weekly_demo_flow.py -v`。

### Task 2: 完整流程验证

**Files:**
- Modify: `scripts/weekly_demo_flow.py`

- [ ] 执行 `python scripts/weekly_demo_flow.py demo`。
- [ ] 检查 JSON 状态为 success、模板编码匹配、报告五章节齐全。
- [ ] 执行 reset 后确认演示目录被删除。

### Task 3: 使用说明

**Files:**
- Create: `docs/weekly-demo-flow.md`

- [ ] 记录命令、输出文件和错误分支。
