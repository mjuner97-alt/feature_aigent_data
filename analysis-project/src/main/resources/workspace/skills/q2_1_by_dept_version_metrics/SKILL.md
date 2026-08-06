---
name: q2_1_by_dept_version_metrics
description: 通过 sql_registry_exec 调预注册 SQL "q2_1_metrics_by_dept_version" 取 Q2-1 打分状态分布 (GaussDB)
---

# Q2-1 指标查询 (按部门 + 版本, 走 sql_registry_exec)

> 共享硬规则 (CSV 路径 / arith 复算 / 空结果 / 直接调用 / python_exec 重试) 已在主 agent AGENTS.md
> 和子 agent sysPrompt (SubagentRegistrar 自动注入 `skills/_common/SKILL.md`) 中, 本 skill 不重复。

> 本 skill 是 `sql_registry_exec` 工具的 GaussDB 验证实例。预注册 SQL 已由 DBA 录入
> MySQL `sql_registry` 表, sql_id = `q2_1_metrics_by_dept_version`, datasource = `gauss`。

业务表: `dsqa_dwd_req_item_app_portrait_wide_inf` (GaussDB schema `remote_app`)
预注册 SQL: `q2_1_metrics_by_dept_version` (在 `sql_registry` 表中)
适用问题: 用户问 "X 部门 + Y 版本 + Q2-1 打分状态/达标率"

## sql_id 与参数 schema

| sql_id | datasource | 参数 | 说明 |
|---|---|---|---|
| `q2_1_metrics_by_dept_version` | gauss | `dept` (string, 必填) | 开发部门, 如 "杭州开发二部" |
| | | `version` (string, 必填) | 版本计划, 如 "2026年7月份版本" |

预注册 SQL (DBA 录入, LLM 不能改):

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

> 中文别名必须用双引号包裹 (GaussDB/openGauss 要求, 否则报 syntax error)。
> LIMIT 由工具内部固定 10000, 不要写 `LIMIT :limit` 占位符。

## 工作流 (analyze_data 必读, 严格按顺序)

### Step 1: 从用户问题提取参数

- `dept`: 开发部门 (例: "杭州开发二部")
- `version`: 版本计划 (例: "2026年7月份版本")

如果用户没指定部门或版本, 追问 -- 不要默认查全部 (会扫全表)。

### Step 2: 直接调 sql_registry_exec 取数

```
sql_registry_exec(
  sqlId="q2_1_metrics_by_dept_version",
  params={"dept":"杭州开发二部", "version":"2026年7月份版本"}
)
```

- ⚠️ **参数名必须在 params_schema 内** -- 多余参数会被工具拒执行 (防注入)。本例只能传 `dept` / `version` (不要传 `limit` / `tableName` / `schema` 等额外参数)。

### Step 3: 用 python_exec + pandas 算指标 (如需)

本预注册 SQL 已含 `in_date = (SELECT MAX(in_date) ...)` 子查询过滤最新数据, 返回的 markdown 表已经是底层宽表行, 不是聚合结果。算 Q2-1 完成率/达标率需 pandas:

```python
import pandas as pd
df = pd.read_csv("/workspace/artifacts/<user>/<task>/sql-xxx.csv")  # 路径从工具返回复制
total = len(df)
if total == 0:
    print("无数据")
else:
    # Q2_1打分状态 实际值 (按业务约定, 通常为 "已打分" / "未打分" 等)
    scored = (df['Q2_1打分状态'] == '已打分').sum()
    # Q2_1是否达标 实际值 (按业务约定, 通常为 "达标" / "不达标" 等)
    passed = (df['Q2_1是否达标'] == '达标').sum()
    print(f"总数={total}, 已打分={scored}, 达标={passed}")
```

### Step 4: 用 arith 复算百分比

```
arith(op="pct", numbers=[<scored>, <total>])    # Q2-1 打分率
arith(op="pct", numbers=[<passed>, <total>])    # Q2-1 达标率
```

### Step 5: 回复用户

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
> sql_registry_exec(
>   sqlId="q2_1_metrics_by_dept_version",
>   params={"dept":"杭州开发二部", "version":"2026年7月份版本"}
> )
> ```
> 
> ### Step 3 模板
> 
> ```python
> import pandas as pd
> df = pd.read_csv("/workspace/artifacts/<user>/<task>/sql-xxx.csv")
> total = len(df)
> passed = (df['Q2_1是否达标'] == '达标').sum()
> print(f"总数={total}, 达标={passed}")
> ```
> 
> ### Step 4 复算
> 
> ```
> arith(op="pct", numbers=[<passed>, <total>])
> ```

## 注意事项

- **必填参数 dept + version**, 用户没指定就追问, 不要默认查全部。
- **多余参数会被拒执行** -- 只能传 dept / version, 传其他参数名 (如 limit / tableName / schema) 会被工具拒。
- **LIMIT 由工具内部固定 10000** -- 不要在 params 里传 limit, 也不要在 SQL 模板里写 `LIMIT :limit` (会触发重复 LIMIT 语法错)。
- **SQL 模板不可改** -- 业务方要改 SQL 需找 DBA 在 sql_registry 表里改 sql_template, LLM 只能传 sql_id + params。
