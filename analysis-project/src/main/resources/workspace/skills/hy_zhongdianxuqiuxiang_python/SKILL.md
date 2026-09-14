---
name: hy_zhongdianxuqiuxiang_python
description: 部门版本 Q2-1 数据
---

## 执行纪律（必须遵守）

本 Skill 已固定执行器和参数，正文流程优先于任何通用工具发现规则：

- 只允许按正文列出的顺序执行；不得跳步、改顺序或提前结束。
- Skill 明确给出的 `toolId`、`sqlId`、`scriptId` 必须原样使用，参数必须按正文传递。
- 禁止调用 `tool_index`、`toolMetaInfo` 或 `router_tool` 来验证、替换或重新发现 Skill 已给出的固定 ID。
- 固定 ID 执行失败时，必须如实报告失败原因，不得改猜其他 SQL/SCRIPT 或回退到工具发现流程。


脚本参数：

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `dept` | string | 是 | 开发部门，例如“杭州开发二部” |
| `version` | string | 是 | 完整版本，例如“2026年7月份版本” |

工作流 (严格按顺序)
#### Step 1: 从用户问题提取参数
- `version`: 从用户提问中提取版本信息

- `dept`: 从用户问题中提取部门信息

#### Step 2: 直接调 script_exec 取数


```
script_exec(
  scriptId="q2_1_metrics_by_dept_version",
  params={"dept":"杭州开发二部","version":"2026年7月份版本"}
)
```


#### Step 3: 下载明细

```
sql_registry_exec(
  sqlId="q2_1_metrics_by_dept_version",
   params={"dept":"杭州开发二部", "version":"2026年7月份版本"},
   downloadFilename="q2_1_杭州开发二部_2026年7月份版本.csv"
 )
 ```

### Step 4：回复用户

- 从 Step 3 工具结果中原样复制 `📥 下载链接:` 后面的 URL；
- 使用 Markdown 链接渲染，例如 `[点击下载 CSV](/redirect/download?shortCode=xxx)`；
- 不要手工编造或修改 shortCode。
