---
name: _common
description: 所有 *_metrics skill 共享的硬规则 (CSV 路径 / arith / 空结果 / 直接调用 / python_exec 重试). SubagentRegistrar 自动注入到子 agent sysPrompt, 主 agent 见 AGENTS.md.
---

# Skill 共享硬规则

> SubagentRegistrar 启动时把本文件内容 prepend 到每个子 agent sysPrompt。
> 主 agent (Supervisor) 见 AGENTS.md (已含相同规则)。各 `*_metrics` skill 不再重复。

## CSV 路径

- 路径只能从工具返回的 `📦 CSV 路径:` 行复制, 含 `<userId>/<taskId>` 前缀, 改写会被 `ArtifactAccessMiddleware` 越权拦截
- `python_exec` 里 `pd.read_csv("<复制的路径>")` 即可, 禁止手工解析 markdown 预览表格

## arith 复算

- 任何加减乘除 / 百分比一律走 `arith` 工具, 哪怕只是 "23.1 - 13.1"
- 禁止 LLM 心算百分比 (小参数模型连 23.1 - 13.1 都会算错)

## 空结果处理

- total=0 直接回复 "无数据", 不要调 arith (避免 0/0)
- 分子=0 不要算达标率, 直接回复 "完成数为 0, 无法算"

## 直接调用

- `wide_table_query` / `clickhouse_query` / `sql_registry_exec` 已直接注册在 Toolkit 上
- **不要走 `router_tool({toolId:"wide_table_query",...})` 元工具路由** -- 浪费 4-5 轮往返

## python_exec 失败重试

- 不要重写整段代码, 只改报错那一行, 上方加 `# fix: <说明>`
- 超过 2 次失败立即停止, 把 code + traceback + 怀疑列出来回复用户
- 失败结果末尾会自动追加 `✦ 失败行` / `✦ 异常类别` / `✦ 常见修法` 提示
