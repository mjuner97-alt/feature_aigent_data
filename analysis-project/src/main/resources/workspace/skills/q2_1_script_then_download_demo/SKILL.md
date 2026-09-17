---
name: q2_1_script_then_download_demo
description: Q2-1 数据
---

# Q2-1 脚本计算与明细下载验证

## 工作流（严格按顺序）

### Step 1：提取参数

从用户问题中提取：

- `dept`：开发部门，例如 `杭州开发二部`
- `version`：完整版本，例如 `2026年7月份版本`

如果缺少 `dept` 或 `version`，先追问，不得默认查全部。

### Step 2：执行脚本（已内置明细下载）

必须直接调用一次 `script_exec`：

```text
script_exec(
  scriptId="q2_1_metrics_by_dept_version",
  params={"dept":"杭州开发二部","version":"2026年7月份版本"}
)
```

脚本内部已固化明细下载文件名（q2_1_明细.csv），**不要**传 `downloadFilename` 参数，
也**不要**再调用 `sql_registry_exec`。

### Step 3：回复用户

- 工具结果会被系统接管（你看到的是占位符）：指标表、图表与下载链接由系统自动附在回答末尾；
- 只需根据问题写简短的业务总结，**不要**复述图表内容、不要编造下载链接或 shortCode。
