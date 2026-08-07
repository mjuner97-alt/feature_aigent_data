---
name: q2_1_by_dept_version_metrics
description: 通过 script_exec 调预注册 Python 脚本 "q2_1_metrics_by_dept_version" 一次完成 SQL 取数 + pandas 算 Q2-1 指标 (GaussDB)
---

# Q2-1 指标查询 (按部门 + 版本, 走 script_exec 一步到位)

> 共享硬规则 (CSV 路径 / arith 复算 / 空结果 / 直接调用 / python_exec 重试) 已在主 agent AGENTS.md
> 和子 agent sysPrompt (SubagentRegistrar 自动注入 `skills/_common/SKILL.md`) 中, 本 skill 不重复。

> 本 skill 是 `script_exec` 工具的验证实例。预注册 Python 脚本已由开发人员录入
> MySQL `script_registry` 表, script_id = `q2_1_metrics_by_dept_version`, datasources = `["gauss"]`。
> 脚本内部完成: SQL 取数 (GaussDB) + pandas 算 总数/已打分/达标数, 一次返回 markdown 表 + JSON 行。

业务表: `dsqa_dwd_req_item_app_portrait_wide_inf` (GaussDB schema `remote_app`)
预注册脚本: `q2_1_metrics_by_dept_version` (在 `script_registry` 表中)
适用问题: 用户问 "X 部门 + Y 版本 + Q2-1 打分状态/达标率"

## script_id 与参数 schema

| script_id | datasources | 参数 | 说明 |
|---|---|---|---|
| `q2_1_metrics_by_dept_version` | `["gauss"]` | `dept` (string, 必填) | 开发部门, 如 "杭州开发二部" |
| | | `version` (string, 必填) | 版本计划, 如 "2026年7月份版本" |

脚本内部 SQL (开发人员录入, LLM 不能改):

```sql
SELECT
  projectzh_no AS "项目编号",
  projectzh_name AS "项目名称",
  dev_dept AS "开发部门",
  version_plan AS "版本计划",
  app AS "涉及应用",
  product_line AS "产品线",
  stat_group AS "统计组",
  score_status_2_1 AS "Q2_1打分状态",
  standard_is_2_1 AS "Q2_1是否达标"
FROM dsqa_dwd_req_item_app_portrait_wide_inf
WHERE dev_dept = :dept
  AND version_plan = :version
  AND in_date = (SELECT MAX(in_date) FROM dsqa_dwd_req_item_app_portrait_wide_inf)
```

脚本内部 pandas 计算 (开发人员录入, LLM 不能改):

```python
total = len(df)
scored = int((df["Q2_1打分状态"] == "已打分").sum())
passed = int((df["Q2_1是否达标"] == "达标").sum())
```

## 工作流 (严格按顺序)

### Step 1: 从用户问题提取参数

- `dept`: 开发部门 (例: "杭州开发二部")
- `version`: 版本计划 (例: "2026年7月份版本")

如果用户没指定部门或版本, 追问 -- 不要默认查全部 (会扫全表)。

### Step 2: 直接调 script_exec 一步到位 (SQL 取数 + pandas 算指标 + 百分比)

```
script_exec(
  scriptId="q2_1_metrics_by_dept_version",
  params={"dept":"杭州开发二部", "version":"2026年7月份版本"}
)
```

- ⚠️ **参数名必须在 params_schema 内** -- 多余参数会被工具拒执行 (防注入)。本例只能传 `dept` / `version`。
- 工具返回 markdown 表 (前 N 行) + 末行 `json: {"total":N,"scored":N,"passed":N,"scored_pct":N,"passed_pct":N}` (程序解析用)。
- **百分比已由脚本算好** (round 2 位小数), LLM 直接读 `scored_pct` / `passed_pct` 即可, **不需要再调 arith 复算**。
- 不需要再调 `python_exec` 写 pandas 代码 -- 脚本内部已经算好了。

### Step 3: 回复用户

中文, 包含部门 + 版本 + 数据日期 (从 SQL 子查询自动取最新 in_date) + 指标数字 (总数 / 打分率 / 达标率) + 业务解读 + 数据来源标注。

> ## 示例 1: 单部门单版本
>
> 用户问: "杭州开发二部 7月版 Q2-1 达标率多少?"
>
> params: `{"dept":"杭州开发二部", "version":"2026年7月份版本"}`
>
> ### Step 2 调用
>
> ```
> script_exec(
>   scriptId="q2_1_metrics_by_dept_version",
>   params={"dept":"杭州开发二部", "version":"2026年7月份版本"}
> )
> ```
>
> 返回 (示意):
> ```
> | 总数 | 已打分 | 达标数 | 打分率 | 达标率 |
> |---:|---:|---:|---:|---:|
> | 45 | 42 | 38 | 93.33% | 84.44% |
>
> json: {"total":45,"scored":42,"passed":38,"scored_pct":93.33,"passed_pct":84.44}
> ```
>
> ### Step 3 回复
>
> "杭州开发二部 2026年7月份版本 Q2-1: 总数 45, 打分率 93.33%, 达标率 84.44%..."

## 注意事项

- **必填参数 dept + version**, 用户没指定就追问, 不要默认查全部。
- **多余参数会被拒执行** -- 只能传 dept / version, 传其他参数名 (如 limit / tableName / schema) 会被工具拒。
- **脚本不可改** -- 业务方要改 SQL 或 pandas 计算逻辑需找开发人员在 `script_registry` 表里改 script_path 指向的 .py 文件, LLM 只能传 script_id + params。
- **替代 sql_registry_exec + python_exec 两步走** -- 一次 script_exec 调用拿到全部数字, 不再需要 LLM 写 pandas 代码, 避免 qwen3:8b 写代码卡死。
