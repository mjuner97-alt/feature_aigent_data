---
name: q2_1_dual_time_demo
description: Q2-1 双时间维度 demo - 脚本同时支持版本计划与季度两种时间入参, 用于端到端验证「版本/季度互不换算」规则
---

# Q2-1 双时间维度 demo (版本计划 / 季度)

## 工作流（严格按顺序）

### Step 1：提取参数

从用户问题中提取：

- `dept`：开发部门，例如 `杭州开发二部`
- `version`：完整版本，例如 `2026年12月份版本`（与 quarter 二选一）
- `quarter`：季度，例如 `2026年4季度`（与 version 二选一）

时间维度只传用户显式提到的一个：

- 用户提版本（含「12月版」「当前版本」「今年以来」「近半年」等变体）→ 只传 `version`
- 用户提季度（含「当季」「Q1」「今年一季度」等变体）→ 只传 `quarter`
- 两者都没提 → 只传 `version`（默认在建版本）
- 两者都提了 → 只传 `version`，季度丢弃
- 问题含需求项/需求子条目且未提时间 → `version`/`quarter` 都不传（时间维度为空，脚本按需求项全历史口径处理）

### Step 2：执行脚本

必须直接调用一次 `script_exec`：

```text
script_exec(
  scriptId="q2_1_metrics_dual_time",
  params={"dept":"杭州开发二部","version":"2026年12月份版本"}
)
```

或（季度口径，quarter 命中时不要传 version）：

```text
script_exec(
  scriptId="q2_1_metrics_dual_time",
  params={"dept":"杭州开发二部","quarter":"2026年4季度"}
)
```

- ⚠️ **version 与 quarter 不要同时传**——脚本按「显式提及哪个传哪个」二选一，同时传会互相污染结果。
- ⚠️ 不要传 `qStart`/`qEnd`（由系统自动补），不要传 `downloadFilename`。

### Step 3：回复用户

- 输出开头有一行「时间入参回显」，说明脚本实际采用的时间口径（timeSource=versionPlan/vestQuarter），业务总结时可引用；
- 工具结果会被系统接管（占位符）：指标表、图表与下载链接由系统自动附在回答末尾；
- 只写简短业务总结，不要复述图表内容、不要编造下载链接。
