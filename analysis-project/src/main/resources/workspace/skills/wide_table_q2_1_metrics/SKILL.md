---
name: wide_table_q2_1_metrics
description: 宽表 dsqa_dwd_req_item_app_portrait_wide_inf 的 Q2-1 打分指标加工 - 部门/产品线/统计组维度
---

# 宽表 Q2-1 打分指标加工

> 本 skill 是 `wide_table_*_metrics` 系列的一个实例。业务方新增 Q2-2 / Q3 / Q4 等指标时,
> 复制本目录改: frontmatter description / 字段映射 / 公式 / python_exec 模板里的列名,
> 即可生成新 skill (如 `wide_table_q3_metrics`), 不要改 Java 代码。
> Agent 通过 SkillRetrievalHook 按用户问题语义自动检索对应 skill。

业务表: `dsqa_dwd_req_item_app_portrait_wide_inf` (schema 由 `wide_table_query` 工具固定为 `remote_app`, 调用时只传表名)
适用问题: 用户问 "X部门/X产品线/X统计组 + X月版本 + Q2-1 的完成率/达标率"

## 字段中英文映射 (8 个核心字段)

| 表字段 | 中文 | 用途 |
|---|---|---|
| projectzh_no | 总行项目编号 | 项目唯一标识 |
| projectzh_name | 总行项目名称 | 项目名 |
| dev_dept | 开发部门 | 维度-部门 |
| version_plan | 版本计划 | 时间筛选 (必填) |
| app | 涉及应用 | 维度-应用 (SQL 保留字, 工具已自动双引号) |
| product_line | 产品线 | 维度-产品线 |
| stat_group | 统计组 | 维度-统计组 |
| score_status_2_1 | Q2-1打分状态 | 完成数计算 (值: 已完成/未完成/进行中) |
| standard_is_2_1 | Q2-1是否达标 | 达标数计算 (值: 已达标/未达标) |

## 4 个公共指标公式

1. **Q2-1完成数** = `score_status_2_1 = '已完成'` 的行数
2. **Q2-1达标数** = `standard_is_2_1 = '已达标'` 的行数
3. **完成率** = 完成数 / 全量
4. **达标率** = 达标数 / 完成数  (注意分母是完成数, 不是全量)

## 工作流 (analyze_data 必读, 严格按顺序)

### Step 1: 从用户问题提取参数

必填:
- `version_plan`: 从 "X月版" 提取, 标准化为 "2026年X月份版本" 格式

维度三选一 (用户没指定就追问, 不要默认查全部):
- `dev_dept`: 部门维度
- `product_line`: 产品线维度
- `stat_group`: 统计组维度

### Step 2: 直接调 wide_table_query 取底层宽表数据

```
wide_table_query(
  table="dsqa_dwd_req_item_app_portrait_wide_inf",
  fields=["projectzh_no","projectzh_name","dev_dept","version_plan","app","product_line","stat_group","score_status_2_1","standard_is_2_1"],
  filters={"dev_dept":"杭州开发二部","version_plan":"2026年7月份版本"}
)
```

> ⚠️ **直接调用, 不要走 router_tool**: `wide_table_query` 已直接注册在 analyze_data 子 agent 的 Toolkit 上, 跳过 `router_tool({toolId:...})` 元工具路由能省 5 轮 LLM 往返 (toolMetaInfo + 拼参失败重试)。

> schema 由工具硬编码为 `remote_app`, 不需要传 `remote_app.dsqa_dwd_...`, 只传表名即可。
> LIMIT 由工具硬编码为 10000, 不需要传 limit 参数, 工具自动取全量 (最多 10000 行)。

工具返回 markdown 预览 + `📦 CSV 路径: <path>` 行 (由 ArtifactHandoffHook 自动落 CSV)。

> 🚨 **硬规则**: CSV 路径只能从 wide_table_query 返回的 `📦 CSV 路径:` 行复制, 不要手工编造, 里面带 `<userId>/<taskId>` 前缀, 改写会被 ArtifactAccessMiddleware 越权拦截。

### Step 3: 用 python_exec + pandas 算 4 个指标

```python
import pandas as pd
df = pd.read_csv("/workspace/artifacts/<user>/<task>/wtq-xxx.csv")  # 路径从工具返回复制
total = len(df)
completion_count = (df['score_status_2_1'] == '已完成').sum()
standard_count = (df['standard_is_2_1'] == '已达标').sum()
print(f"全量={total}, 完成数={completion_count}, 达标数={standard_count}")
```

如果 total=0, 直接回复 "无数据", 不要调 arith。
如果 completion_count=0, 不要算达标率, 直接回复 "完成数为 0, 无法算达标率"。

### Step 4: 用 arith 复算百分比 (BigDecimal, 双重保险)

```
arith(op="pct", numbers=[<completion_count>, <total>])       # 完成率
arith(op="pct", numbers=[<standard_count>, <completion_count>])  # 达标率
```

arith 返回 BigDecimal 精度结果, 以此为准回复用户。

### Step 5: 回复用户

中文, 包含:
- 4 个数字 (全量/完成数/达标数/完成率/达标率)
- 业务解读 (例: "完成率 80%, 达标率 90% - 达标率高于完成率说明已完成的项目大多达标")
- 数据来源标注 (基于 N 行数据)

## 维度枚举 (常用值)

- 开发部门: 杭州开发一部/二部/三部/四部/五部
- 版本计划: 2026年7月份版本, 2026年8月份版本
- 产品线: 个人信贷产品线, 对公信贷产品线, 信用卡产品线, 风控产品线
- 统计组: 信贷应用开发组, 个贷组, 信用卡组, 风控组

## 示例 1: 部门维度

用户问: "杭州二部8月版Q2-1的完成率、达标率是多少?"

filters: `{"dev_dept":"杭州开发二部","version_plan":"2026年8月份版本"}`

注意: 业务口语 "杭州二部" 对应表字段值 "杭州开发二部", 要做映射。

## 示例 2: 产品线维度

用户问: "个贷产品线8月版Q2-1的完成率、达标率是多少?"

filters: `{"product_line":"个人信贷产品线","version_plan":"2026年8月份版本"}`

注意: 业务口语 "个贷产品线" 对应表字段值 "个人信贷产品线"。

## 示例 3: 统计组维度

用户问: "信贷应用开发组8月版Q2-1的完成率、达标率是多少?"

filters: `{"stat_group":"信贷应用开发组","version_plan":"2026年8月份版本"}`

## 注意事项

- **version_plan 必填**, 没有就追问用户。
- **维度字段三选一** (部门/产品线/统计组), 用户没指定就追问, 不要默认查全部。
- **禁止用 SQL GROUP BY/COUNT 算指标** -- SQL 只取数, 计算走 python_exec + pandas。
- **禁止 LLM 心算百分比** -- 走 arith 工具 (BigDecimal 精度)。
- 空结果 (total=0) 时返回 "无数据", 不要算 0/0。
- 多维度组合 (如部门+产品线) 也支持, filters 里加多个键即可。
- 业务口语与表字段值有差异时要映射 (例: "杭州二部" -> "杭州开发二部", "个贷产品线" -> "个人信贷产品线")。
