# Q2-1 部门版本指标报告构建指导

本文说明如何新增或修改 `q2_1_by_dept_version_metrics_report` 类型的固定报表，覆盖 SQL、ECharts、HTML、Skill 以及两个注册表的配置。示例以当前实现为准：

- Skill：`workspace/skills/q2_1_by_dept_version_metrics_report/SKILL.md`
- 模板：`q2_1_by_dept_version_metrics/report-v1`
- SQL：`q2_1_report_by_dept_version`
- 数据源：GaussDB，业务表 `remote_app.dsqa_dwd_req_item_app_portrait_wide_inf`

## 1. 总体职责

固定报表必须把业务计算和展示代码放在服务端注册表中，LLM 只传参数并引用报告链接。

| 组件 | 负责内容 | 不应负责 |
|---|---|---|
| `sql_registry` | 参数化 SELECT、筛选、聚合、百分比、排序、补位，返回展示数据 | HTML、CSS、ECharts option、任意 DDL/DML |
| `presentation_template_registry` | ECharts JSON 模板、HTML 模板、变量 schema、SQL 绑定 | 查询业务表、临时拼接 SQL |
| `SKILL.md` | 触发条件、必填参数、工具调用顺序、最终回复格式 | 复制完整 HTML/ECharts 源码、重新计算 SQL 已给出的指标 |
| `presentation_render` | 校验模板和变量，执行 SQL/绑定 resultRef，生成受控 HTML 报告 | 把完整报告源码返回给模型 |

推荐数据流：

```text
用户问题
  -> load_skill_through_path
  -> presentation_render(templateId, params)
  -> presentation_template_registry
  -> sql_registry(sql_id)
  -> GaussDB 返回一行 variables_json + summary_json
  -> 服务端绑定变量并渲染 ECharts/HTML
  -> reportId + markdownLink
```

## 2. SQL 脚本写法

### 2.1 基本规则

- 只允许预注册的 `SELECT`，不要写 `INSERT`、`UPDATE`、`DELETE`、DDL、注释符或多条语句。
- 所有用户输入使用命名参数 `:dept`、`:version`，禁止字符串拼接。
- `params_schema` 中声明的参数必须与 SQL 占位符一致；调用方不得传未声明参数。
- 固定报表 SQL 应尽量在数据库完成聚合、百分比、排序和缺省补位，避免把明细传给 LLM。
- 不要在模板末尾写 `LIMIT :limit`。工具会对没有 LIMIT 的查询增加固定行数上限；报告聚合 SQL 应保证只返回一行。
- 表名和 schema 使用真实数据源名称。当前 GaussDB SQL 使用 `remote_app.dsqa_dwd_req_item_app_portrait_wide_inf`。
- 分母可能为 0 时使用 `CASE` 或 `NULLIF`，不要让报告因除零失败。

### 2.2 给展示模板的 SQL 契约

绑定 `presentation_template_registry` 的 SQL 必须返回恰好一行，且列名固定为：

- `variables_json`：JSON 对象，供 ECharts 和 HTML 模板绑定。
- `summary_json`：JSON 对象，供聊天摘要和最终 Markdown 表格使用。

当前 Q2-1 报表使用 `json-envelope-v1` adapter，因此必须直接返回上述两列。`rows-v1` 只适合把普通结果行映射到 `records` 的简单模板，不能替代 Q2-1 的趋势数组、固定部门补位和格式化逻辑。

推荐的聚合逻辑：

```sql
SELECT
  :dept AS "department",
  :version AS "version",
  COUNT(*) AS "total",
  COALESCE(SUM(CASE WHEN score_status_2_1 = '已打分' THEN 1 ELSE 0 END), 0) AS "scored",
  COALESCE(SUM(CASE WHEN standard_is_2_1 = '达标' THEN 1 ELSE 0 END), 0) AS "passed"
FROM remote_app.dsqa_dwd_req_item_app_portrait_wide_inf
WHERE dev_dept = :dept
  AND version_plan = :version
  AND in_date = (
    SELECT MAX(in_date)
    FROM remote_app.dsqa_dwd_req_item_app_portrait_wide_inf
  )
```

百分比应明确口径：

```sql
CASE WHEN COUNT(*) = 0 THEN 0
     ELSE ROUND(100.0 * SUM(CASE WHEN score_status_2_1 = '已打分' THEN 1 ELSE 0 END) / COUNT(*), 2)
END AS "scoredPct"
```

### 2.3 SQL 自检

执行注册前，先用同一数据源验证：

```sql
-- 参数替换只用于 DBA 本地验证，注册表中仍必须使用 :dept / :version
SELECT ...
WHERE dev_dept = '杭州开发二部'
  AND version_plan = '2026年7月份版本';
```

- 检查最新 `in_date` 是否确实为全表最大日期。
- 检查无数据时是否仍返回一行、数值为 0，而不是返回空结果集。
- 检查中文状态值完全匹配：`已打分`、`达标`。
- 检查列别名与 adapter/变量 schema 一致，避免 `passed` 写成 `pass`。
- 检查 SQL 只读、执行耗时和索引；大表过滤条件必须包含部门、版本和日期。

## 3. `sql_registry` 表配置

### 3.1 字段说明

当前 GaussDB 表字段如下：

| 字段 | 要求 |
|---|---|
| `sql_id` | 唯一、稳定、snake_case；Skill 和模板通过它绑定，不要随意改名 |
| `name` | 面向管理人员的中文名称 |
| `description` | 说明数据源、参数、返回内容和适用模板 |
| `datasource` | 只能使用实际支持的数据源，如 `gauss`、`mysql`、`clickhouse` |
| `sql_template` | 参数化只读 SELECT；使用 `:参数名` |
| `params_schema` | JSON 数组：`name/type/required/description`；必须覆盖全部参数 |
| `enabled` | `1` 才可执行；下线先置 `0`，不要删除仍被模板引用的记录 |
| `created_by` | 记录 DBA、Flyway 或变更来源 |

参数 schema 示例：

```json
[
  {"name":"dept","type":"string","required":true,"description":"开发部门，如 杭州开发二部"},
  {"name":"version","type":"string","required":true,"description":"完整版本名称，如 2026年7月份版本"}
]
```

### 3.2 注册示例

新增或修改表记录应通过 Flyway 迁移，不要在生产库手工执行一次性 INSERT：

```sql
INSERT INTO sql_registry
  (sql_id, name, description, datasource, sql_template, params_schema, created_by)
VALUES
  ('q2_1_report_by_dept_version',
   '部门+版本 Q2-1 报告聚合查询',
   '返回固定报表所需的部门、版本、总数、已打分数和达标数。',
   'gauss',
   'SELECT ... WHERE dev_dept = :dept AND version_plan = :version',
   '[{"name":"dept","type":"string","required":true}, {"name":"version","type":"string","required":true}]',
   'flyway')
ON DUPLICATE KEY UPDATE
  name = VALUES(name),
  description = VALUES(description),
  datasource = VALUES(datasource),
  sql_template = VALUES(sql_template),
  params_schema = VALUES(params_schema);
```

注意：项目同时存在 MySQL 风格和 openGauss 迁移历史。新增迁移必须以当前目标库的 Flyway 方言为准；openGauss 版本使用 `BIGSERIAL`、`SMALLINT` 和触发器维护 `updated_at`，不要照抄 MySQL 的 `AUTO_INCREMENT` 或 `ON UPDATE`。

## 4. ECharts 脚本写法

ECharts 内容存储在 `presentation_template_registry.echarts_template`，必须是合法 JSON 模板，不是 JavaScript 文件。

- 用 `{{title}}`、`{{versions}}`、`{{scoredRates}}` 等变量占位。
- 数组变量保持 JSON 数组类型，不要把数组包成带引号的字符串。
- 图表颜色、坐标轴、图例、阈值线等固定规则写入模板；Skill 不重复描述或传入这些配置。
- 不允许变量注入 `formatter`、`script`、函数体或任意 URL。
- `formatter` 只能使用服务端允许的固定字符串，例如 `"{value}%"`；不要接受用户传入的 JavaScript 函数。
- 版本、部门顺序和缺失部门补位由 SQL/adapter 完成，ECharts 只负责绘制。

简化示例：

```json
{
  "title": {"text": "{{title}}", "left": "center"},
  "tooltip": {"trigger": "axis"},
  "xAxis": {"type": "category", "data": "{{versions}}"},
  "yAxis": {"type": "value", "min": 0, "max": 100},
  "series": [
    {"name":"打分率", "type":"line", "data":"{{scoredRates}}"},
    {"name":"达标率", "type":"line", "data":"{{passedRates}}"}
  ]
}
```

提交前使用 JSON 解析器校验模板；不能只依赖肉眼检查。模板最大体积和变量最大体积遵循 `presentation_render` 的工具限制。

## 5. HTML 脚本写法

HTML 内容存储在 `html_template`，由服务端渲染为受控报告。

- 使用 `{{name}}` 绑定转义后的文本。
- 使用 `{{#records}}...{{/records}}` 渲染数组，使用 `{{#summary}}...{{/summary}}` 渲染可选对象。
- `{{@echarts}}` 是受控的 ECharts 插槽，只能由渲染器生成；不要从变量传入 HTML。
- 所有用户或数据库字符串必须 HTML 转义；数值按 JSON 数值节点处理。
- CSS 使用模板内的稳定 class 名称，表格宽度、图表高度和移动端横向滚动要明确设置。
- 不引用外部 CDN、远程脚本或用户传入的 `src`；报告页使用本地受控 ECharts 资源。
- 不在 HTML 模板内执行任意内联脚本。若必须初始化图表，由渲染器生成受控的 JSON 数据块和固定初始化代码。

示例片段：

```html
<section class="q21-section">
  <h2>ECharts 图表</h2>
  {{@echarts}}
</section>
<table class="q21-table">
  <tbody>
    {{#records}}
    <tr>
      <td>{{department}}</td><td>{{version}}</td>
      <td>{{total}}</td><td>{{scored}}</td><td>{{passed}}</td>
      <td>{{scoredPctText}}</td><td>{{passedPctText}}</td>
    </tr>
    {{/records}}
  </tbody>
</table>
```

## 6. `presentation_template_registry` 表配置

### 6.1 字段说明

| 字段 | 要求 |
|---|---|
| `template_id` | 全局唯一且带版本，如 `q2_1_by_dept_version_metrics/report-v1` |
| `name` / `description` | 说明业务用途和模板版本，不写隐含的临时参数 |
| `echarts_template` | 合法 JSON 模板；ECharts-only 模板可为空 HTML |
| `html_template` | 合法 HTML 模板；HTML-only 模板可为空 ECharts |
| `variable_schema` | JSON 数组，声明每个变量的名称、类型和是否必填 |
| `data_provider_type` | 固定 SQL 报表填 `sql`；临时小数据才使用 `inline` |
| `data_provider_id` | `data_provider_type=sql` 时必须等于启用的 `sql_registry.sql_id` |
| `data_adapter` | 例如 `rows-v1`；必须是服务端已实现的 adapter |
| `parameter_mapping` | JSON 对象；为空映射使用 `{}`，不要填写随意字符串 |
| `enabled` | 只有完整通过验证的版本才设为 `1` |

当前 Q2-1 绑定关系：

```text
template_id       = q2_1_by_dept_version_metrics/report-v1
data_provider_type= sql
data_provider_id  = q2_1_report_by_dept_version
data_adapter      = json-envelope-v1
parameter_mapping = {}
```

### 6.2 变量 schema 注意事项

```json
[
  {"name":"title","type":"string","required":true},
  {"name":"versions","type":"array","required":true},
  {"name":"scoredRates","type":"array","required":true},
  {"name":"passedRates","type":"array","required":true},
  {"name":"records","type":"array","required":true},
  {"name":"summary","type":"object","required":false},
  {"name":"dataSource","type":"string","required":true},
  {"name":"dataDate","type":"string","required":false}
]
```

- schema 中的名称必须同时出现在 ECharts 或 HTML 模板中，或由 adapter 明确生成。
- `array/object/string/number/boolean` 类型必须与实际 JSON 类型一致。
- schema 未声明的字段应被渲染器拒绝；不要为了“兼容”把 schema 改成任意对象。
- 修改字段契约时创建 `report-v2`，保留旧版本一段兼容期；不要直接破坏正在使用的 `report-v1`。

## 7. Skill.md 写法

Skill 应保持短小，完整模板代码留在注册表。所有模型必须遵守的规则写成列表、表格或代码块，不要放在引用块中。

最小结构：

```markdown
---
name: q2_1_by_dept_version_metrics_report
description: 通过预注册 SQL 和 presentation_render 生成 Q2-1 部门版本报告
---

# Q2-1 部门版本指标报告

## 参数
- `dept`: 必填，完整开发部门名称
- `version`: 必填，完整版本计划名称

## 调用
presentation_render(
  templateId="q2_1_by_dept_version_metrics/report-v1",
  params={"dept":"杭州开发二部","version":"2026年7月份版本"}
)
```

Skill 中必须写清：

- 参数缺失时追问，不能默认查全表。
- 只调用启用的精确 `templateId` 和 `sqlId`。
- 若先调用 `sql_registry_exec`，必须使用 `referenceOnly=true` 并把 `resultRef` 交给 `presentation_render`，避免重复查询。
- 数字只读取本次工具返回的 `summary`，不要使用示例数字或自行改写百分比。
- 最终回复保留结论、Markdown 数据表、数据来源和工具返回的 `markdownLink`；不输出 HTML、CSS、JavaScript 或 ECharts option。

## 8. 注册与部署顺序

1. 新增 Flyway 迁移，先注册或更新 `sql_registry`。
2. 使用 DBA 账号验证 SQL 参数、返回行数、字段别名和无数据边界。
3. 注册 `presentation_template_registry`，填写 ECharts、HTML、变量 schema 和 SQL 绑定。
4. 确认 `data_provider_id` 指向已启用 SQL，Q2-1 的 `data_adapter` 为 `json-envelope-v1`。
5. 更新 `SKILL.md` 的模板 ID、参数和回复契约。
6. 重启或热加载后检查 `sql_list` 与模板管理接口是否能看到启用记录。
7. 执行一次真实 `presentation_render`，确认报告链接可访问且只生成一次。

## 9. 验收清单

- [ ] `sql_id`、`template_id` 唯一且命名稳定。
- [ ] SQL 只有参数化 SELECT，参数 schema 无遗漏、无多余项。
- [ ] SQL 无数据时仍返回可渲染的 0 值或明确空状态。
- [ ] 报表 SQL 返回一行，或 adapter 对多行结果有明确契约。
- [ ] ECharts 模板可被 JSON 解析器解析。
- [ ] HTML 模板只使用已声明变量和受控 `{{@echarts}}` 插槽。
- [ ] 所有文本已转义，模板不加载远程脚本，不接收任意 JavaScript。
- [ ] 模板绑定的 SQL `enabled=1`，且数据源为 `gauss`。
- [ ] Skill 不包含完整图表/HTML 源码，不输出本地 artifact 路径。
- [ ] 最终链接原样使用 `markdownLink`，报告归属和过期校验正常。

## 10. 常见错误

| 现象 | 原因 | 修复 |
|---|---|---|
| `sql_id 不存在或已禁用` | SQL 未迁移、ID 拼错或 `enabled=0` | 先查 `sql_list` 和注册表 |
| `参数不在 params_schema` | Skill 传了 `limit`、`tableName` 等额外参数 | 只传 schema 声明的参数 |
| 模板找不到 SQL | `data_provider_id` 与 `sql_id` 不一致 | 修改模板绑定或创建对应 SQL |
| 变量校验失败 | SQL/adapter 字段名或 JSON 类型与 `variable_schema` 不一致 | 统一字段名和类型，必要时创建 v2 |
| 图表空白 | ECharts JSON 非法、数组被序列化成字符串或插槽未生成 | 先做 JSON 解析和真实渲染测试 |
| 报告重复查询 | 先执行 SQL 后未传 `resultRef` | 使用 `referenceOnly=true` + `resultRef` |
| openGauss 迁移失败 | 使用了 MySQL DDL，如 `AUTO_INCREMENT`、`ON UPDATE` | 按 GaussDB 迁移模板使用 `BIGSERIAL` 和触发器 |

## 11. 相关实现文件

- `workspace/skills/q2_1_by_dept_version_metrics_report/SKILL.md`
- `db/migration/gauss/V20260807.2__sql_registry.sql`
- `db/migration/gauss/V20260818.1__presentation_template_registry.sql`
- `db/migration/gauss/V20260818.2__presentation_sql_data_provider.sql`
- `db/migration/gauss/V20260818.3__q21_json_envelope_provider.sql`
- `docs/table-mertics/sql-registry.sql`
- `docs/prompt/presentation-template-rendering-plan.md`
