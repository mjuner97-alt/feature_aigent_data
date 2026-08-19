# 展示报表注册配置生成 Prompt

本文面向业务人员。把下面“可复制 Prompt”完整交给 LLM，再填写原始 SQL、参数说明、指标口径、ECharts 样式和 HTML 样式。LLM 应生成：

1. `sql_registry` 中 `q2_1_report_by_dept_version` 的插入/更新脚本。
2. `presentation_template_registry` 中 `q2_1_by_dept_version_metrics/report-v1` 的插入/更新脚本。
3. 两张表绑定关系和配置自检结果。

## 使用前准备

业务人员至少要提供：

- 一条可以执行的参数化原始 SQL。
- 每个 `:param` 的类型、是否必填和示例值。
- 指标计算口径，例如“已打分数”和“达标数”如何判断。
- 图表类型、标题、颜色、图例、坐标轴和标签要求。
- HTML 页面、表头、列顺序、颜色和响应式要求。
- 是否需要固定部门补位、排序、合计行和无数据展示。

原始 SQL 可以返回明细。LLM 的任务是将其改造成固定报告 SQL，在数据库中完成聚合和 JSON 信封组装，不要把明细数据交给模型二次加工。

## 可复制 Prompt

```text
你是一名熟悉 openGauss、PostgreSQL JSON 函数、ECharts、HTML/CSS、Flyway 和服务端模板绑定的高级开发工程师。

请根据我提供的“原始参数化 SQL、参数说明、指标口径、ECharts 样式要求、HTML 样式要求”，生成本项目可使用的报表注册配置 SQL。

一、必须生成的配置

1. 向 GaussDB 的 sql_registry 插入或更新一条记录：
   - sql_id = q2_1_report_by_dept_version
   - datasource = gauss
   - enabled = 1
   - SQL 使用命名参数，不允许把参数值直接拼入 SQL。
   - SQL 必须是只读 SELECT。
   - SQL 最终必须恰好返回一行、两列：variables_json 和 summary_json。
   - variables_json 必须是 JSON 对象，包含 ECharts 和 HTML 所需的全部模板变量。
   - summary_json 必须是 JSON 对象，包含聊天回复需要的部门、版本、总数、已打分数、达标数、打分率文本和达标率文本。
   - 使用 json_build_object/json_build_array/json_agg 等 openGauss/PostgreSQL 兼容 JSON 函数。
   - 百分比保留两位小数，分母为 0 时返回 0.00%，不能除零。
   - params_schema 必须完整声明原始 SQL 中的所有 :param，不能多也不能少。

2. 向 GaussDB 的 presentation_template_registry 插入或更新一条记录：
   - template_id = q2_1_by_dept_version_metrics/report-v1
   - data_provider_type = sql
   - data_provider_id = q2_1_report_by_dept_version
   - data_adapter = json-envelope-v1
   - parameter_mapping = {}
   - enabled = 1
   - echarts_template 必须是合法 JSON 模板，不是 JavaScript 对象或函数。
   - html_template 必须使用 {{name}}、{{#records}}...{{/records}}、{{#summary}}...{{/summary}} 和受控 {{@echarts}} 插槽。
   - variable_schema 必须声明 variables_json 中全部顶层变量的 name/type/required。
   - 模板不得接收或执行用户提供的 HTML、JavaScript、formatter 函数、URL 或 ECharts option。

二、SQL 转换规则

1. 保留原始 SQL 的表、WHERE 条件和命名参数语义。
2. 原始 SQL 如果返回明细，使用 CTE 将它命名为 source_data，再聚合出：
   - total：总记录数。
   - scored：满足“已打分口径”的记录数。
   - passed：满足“达标口径”的记录数。
   - scored_rate：scored * 100.0 / total，保留两位小数。
   - passed_rate：passed * 100.0 / total，保留两位小数。
3. 若要求固定部门补位，使用部门维表 CTE + LEFT JOIN 或等价方式，并按明确 sort_no 排序；缺失部门显示“-”。
4. 不得虚构表字段、状态枚举、部门名称或业务口径。信息不足时先列出“需要业务确认的问题”，不要输出猜测性的最终 SQL。
5. 注册表中的 sql_template 是 SQL 字符串：若外层使用普通单引号，内部单引号必须写成两个单引号；若使用 `$query$...$query$`，内部单引号保持原样，不能重复转义。
6. sql_template 内不得出现分号、SQL 注释、DDL 或 DML。

三、ECharts 模板规则

1. 数组变量使用 JSON 类型占位，例如：
   "data":"{{versions}}"
2. 固定颜色、图例、坐标轴、标签、目标线和字体直接写在 echarts_template 中。
3. 只允许固定字符串 formatter，例如 "{value}%" 或 "{c}%"；禁止 JavaScript 函数。
4. series 名称必须与 legend 名称一致。
5. y 轴百分比范围默认 0 到 100；如果我的要求不同，以我的要求为准。

四、HTML 模板规则

1. 页面包含 UTF-8 和 viewport。
2. 图表通过 {{@echarts}} 插槽插入。
3. 明细表使用 {{#records}} 循环，汇总行按需使用 {{#summary}}。
4. 固定表头、列顺序、配色、对齐和响应式横向滚动写入模板 CSS。
5. 不加载 CDN、远程脚本、远程字体或用户传入资源。
6. 不写任意内联 JavaScript；图表初始化由服务端渲染器负责。

五、openGauss/Flyway 输出要求

1. 输出一个完整迁移文件内容，顺序必须是：
   - 先 INSERT/UPDATE sql_registry。
   - 再 INSERT/UPDATE presentation_template_registry。
2. 长 SQL、ECharts、HTML 和 schema 分别使用清晰的 dollar-quoted 标签，例如 $query$、$echarts$、$html$、$schema$，但必须确保目标环境和项目迁移风格支持。
3. UPSERT 语法必须匹配本项目当前 GaussDB/Flyway 迁移约定。如果沿用现有 ON DUPLICATE KEY UPDATE，保持与已有迁移一致；如果改用 ON CONFLICT，必须说明运行环境兼容性依据，不能混用两种方言。
4. 不生成 CREATE TABLE；表结构已经存在。
5. 不修改旧 Flyway 文件；建议新的迁移文件名，并说明它必须晚于当前最新版本。

六、输出格式

严格按以下顺序输出，不要省略：

A. 需求解析
- 参数清单。
- 指标口径。
- variables_json 字段清单。
- summary_json 字段清单。
- 模板变量与来源映射表。

B. 需要业务确认的问题
- 没有问题时写“无”。
- 有关键问题时停止生成最终 SQL，只给出待确认项和建议，不要自行猜测。

C. 完整 Flyway 迁移 SQL
- 一个 sql 代码块。
- 必须能直接作为新迁移文件内容进行 DBA 审核。

D. 配置自检
- 所有命名参数都在 params_schema 中声明。
- SQL 恰好返回 variables_json 和 summary_json。
- variables_json 与 variable_schema 一致。
- ECharts 中所有占位变量都已声明。
- HTML 中所有占位变量都已声明。
- data_provider_id 与 sql_id 一致。
- data_adapter 为 json-envelope-v1。
- 无除零风险、无任意脚本注入、无远程资源。

七、我的输入

【原始参数化 SQL】
{{在这里粘贴 SQL}}

【参数说明】
{{逐项填写参数名、类型、是否必填、示例值}}

【指标口径】
{{填写 total/scored/passed 以及其他指标的计算规则}}

【ECharts 样式要求】
{{填写图表类型、标题、图例、颜色、坐标轴、标签、目标线等}}

【HTML 样式要求】
{{填写页面结构、表头、字段顺序、颜色、字体、合计行、响应式等}}

【固定排序与缺失项补位】
{{填写部门/版本顺序；不需要时填写“无”}}

【数据来源显示文本】
{{例如 GaussDB remote_app.dsqa_dwd_req_item_app_portrait_wide_inf}}
```

## Q2-1 输入示例

以下内容可替换上述 Prompt 的“我的输入”部分：

```text
【原始参数化 SQL】
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
  AND in_date = (
    SELECT MAX(in_date)
    FROM dsqa_dwd_req_item_app_portrait_wide_inf
  )

【参数说明】
- dept：string，必填，完整开发部门名称，例如“杭州开发二部”。
- version：string，必填，完整版本计划名称，例如“2026年7月份版本”。

【指标口径】
- total：符合筛选条件的记录总数。
- scored：Q2_1打分状态等于“已打分”的记录数。
- passed：Q2_1是否达标等于“达标”的记录数。
- scored_rate：scored / total * 100，保留两位小数。
- passed_rate：passed / total * 100，保留两位小数。
- total 为 0 时两个百分比均为 0.00%。

【ECharts 样式要求】
- 折线图，标题为“部门 + 版本 + Q2-1 指标报告”。
- 打分率为蓝色实线，达标率为绿色虚线。
- y 轴范围 0 到 100，标签显示百分号。
- 图例置底，数据点显示两位小数百分比。

【HTML 样式要求】
- 上方展示图表，下方展示部门明细表。
- 表头为红底白字，包含开发部门、版本、总数、已打分、达标数、打分率、达标率。
- 汇总行加粗，偶数行浅色背景，表格在窄屏允许横向滚动。
- 页面显示数据来源和可选统计日期。

【固定排序与缺失项补位】
按业务确认的部门顺序输出；没有数据的部门显示“-”。禁止自行编造部门清单，必须使用我提供的正式清单。

【数据来源显示文本】
GaussDB remote_app.dsqa_dwd_req_item_app_portrait_wide_inf
```

## 人工审核重点

LLM 生成内容不能直接跳过审核上线。DBA 和开发人员至少检查：

- 原始 SQL 是否应补 `remote_app` schema，最新日期口径是全表还是当前部门/版本内最大日期。
- LLM 是否错误地把中文别名当成真实字段继续引用。
- `sql_template` 内部单引号是否正确转义。
- 固定部门清单是否来自业务正式口径，而不是模型补全。
- ECharts/HTML 占位符与 `variable_schema` 是否逐项一致。
- 迁移版本号是否唯一，UPSERT 方言是否能在目标 GaussDB 实际执行。
- 先在测试库执行 `sql_registry` 内的查询，再调用一次 `presentation_render` 验证完整报告。
