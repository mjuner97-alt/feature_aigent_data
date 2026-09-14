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
- `downloadFilename`：固定使用 `q2_1_明细.csv`

如果缺少 `dept` 或 `version`，先追问，不得默认查全部。

### Step 2：先执行 Python 脚本

必须直接调用一次 `script_exec`：

```text
script_exec(
  scriptId="q2_1_metrics_by_dept_version",
  params={"dept":"杭州开发二部","version":"2026年7月份版本"}
)
```


### Step 3：执行 SQL 并生成下载短链

```text
sql_registry_exec(
  sqlId="q2_1_metrics_by_dept_version",
  params={"dept":"杭州开发二部","version":"2026年7月份版本"},
  downloadFilename="q2_1_明细.csv"
)
```

### Step 4：回复用户

- 简要总结 Step 2 的脚本计算结果；
- 从 Step 3 工具结果中原样复制 `📥 下载链接:` 后面的 URL；
- 使用 Markdown 链接渲染，例如 `[点击下载 CSV](/redirect/download?shortCode=xxx)`；
- 不要手工编造或修改 shortCode。


