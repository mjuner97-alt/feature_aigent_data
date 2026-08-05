---
name: q2_1_metrics_download_demo
description: CSV 下载能力验证 demo - 跑 sql_registry_exec 取 Q2-1 数据后调 generate_csv_download_url 生成短链给用户下载
---

# Q2-1 数据下载 demo (CSV 短链下载能力验证)

> 本 skill 验证 CsvDownloadTool + RedirectController 的端到端链路:
> sql_registry_exec 落 CSV -> generate_csv_download_url 生成短链 -> 用户点链接下载到本地。
> 复用 q2_1_by_dept_version_metrics 的预注册 SQL, 只多一步生成下载链接。

业务表: `dsqa_dwd_req_item_app_portrait_wide_inf` (GaussDB schema `remote_app`)
预注册 SQL: `q2_1_metrics_by_dept_version`
适用问题: 用户问 "X 部门 + Y 版本 + Q2-1 数据下载" / "导出 Q2-1 报表" / "把这个查询结果存成 CSV"

## 工作流 (严格按顺序)

### Step 1: 从用户问题提取参数

必填 (用户没指定就追问, 不要默认查全部):
- `dept`: 开发部门 (例: "杭州开发二部")
- `version`: 版本计划 (例: "2026年7月份版本")

### Step 2: 直接调 sql_registry_exec 取数

```
sql_registry_exec(
  sqlId="q2_1_metrics_by_dept_version",
  params={"dept":"杭州开发二部", "version":"2026年7月份版本"}
)
```

- ⚠️ **直接调用, 不要走 router_tool**: `sql_registry_exec` 已直接注册在 analyze_data 子 agent 的 Toolkit 上, 一次调通。
- ⚠️ **参数名必须在 params_schema 内** -- 只能传 `dept` / `version`, 多余参数会被工具拒执行 (防注入)。
- 工具返回 markdown 预览 + `📦 完整数据已保存为 CSV artifact:` 行 + 下面的 `路径: /workspace/artifacts/<user>/<task>/<file>.csv` 行 (由 ArtifactHandoffHook 自动落盘)。
- 🚨 **CSV 路径只能从工具返回的 `路径:` 行复制**, 不要手工编造, 里面带 `<userId>/<taskId>` 前缀, 改写会被 ArtifactAccessMiddleware 越权拦截。

### Step 3: 调 generate_csv_download_url 生成下载短链

把上一步复制的 agentPath 传给 router_tool 元工具 (generate_csv_download_url 通过 ToolRoutersIndex 注册到 router_tool 下):

```
router_tool(paramsJson='{"toolId":"generate_csv_download_url","agentPath":"/workspace/artifacts/<user>/<task>/<file>.csv"}')
```

- ⚠️ **agentPath 必须从 Step 2 工具结果复制**, 不要手写或猜测。
- 工具校验: agentPath 必须以 `/workspace/artifacts/` 开头且不含 `..`, 否则拒绝。
- 工具返回: `CSV 下载链接已生成: /redirect/download?shortCode=<16位BASE62>`

### Step 4: 回复用户

中文, 包含:
- 部门 + 版本 + 数据日期 (从 SQL 子查询自动取最新 in_date)
- 数据规模 (N 行, 列名清单)
- **下载链接**: 必须用 markdown 链接语法渲染成可点击的超链接, 不要写成纯文本
- 提示用户链接长期有效 (短链 16 位密钥, 不可枚举), 但任务结束后 artifact 目录若被清理, 链接会 404

- ⚠️ **下载链接必须用 markdown 链接语法** `[文本](url)`, 不要写裸 URL. 前端只渲染 markdown 链接为可点击 `<a>`, 裸 URL 是纯文本无法点击.
- ⚠️ **链接 URL 用相对路径** `/redirect/download?shortCode=xxx`, 不要带 host (前端会按当前 origin 拼绝对 URL, 经 vite proxy 转发到后端).
- ⚠️ **链接文本用动词短语**, 如 "点击下载 CSV" / "下载完整数据 (80 行)", 不要用 shortCode 当文本.

### Step 5 (可选): 同时算指标

如果用户除了下载还要看指标, 在 Step 3 之后调 arith 算 Q2-1 打分率/达标率:

```
arith(op="pct", numbers=[<scored>, <total>])    # Q2-1 打分率
arith(op="pct", numbers=[<passed>, <total>])    # Q2-1 达标率
```

- 禁止 LLM 心算百分比, 必须走 arith (BigDecimal 精度)。
- 如果 total=0, 直接回复 "无数据, 无可下载", 不要调 arith 也不要生成下载链接。

## 示例: 完整 E2E

用户问: "杭州开发二部 7月版 Q2-1 数据导出一份给我"

### Step 2 调用

```
sql_registry_exec(
  sqlId="q2_1_metrics_by_dept_version",
  params={"dept":"杭州开发二部", "version":"2026年7月份版本"}
)
```

工具返回 (示例, agentPath 中 user/task 是真实值):

```
[sql_registry_exec] sqlId=q2_1_metrics_by_dept_version params={dept=杭州开发二部, version=2026年7月份版本}

| 项目编号 | 项目名称 | 开发部门 | 版本计划 | ... |
|---|---|---|---|---|
| P001 | xxx系统 | 杭州开发二部 | 2026年7月份版本 | ... |
| ... |

[sql_registry_exec] 共 45 行, 耗时 320 ms

📦 完整数据已保存为 CSV artifact:
  路径: /workspace/artifacts/alice/task_2026_08_03_x1y2/sqlregistryex-3f9c-aaaa.csv
  shape: (45, 8)
```

### Step 3 调用 (注意 agentPath 必须从上面 "路径:" 行完整复制)

```
router_tool(paramsJson='{"toolId":"generate_csv_download_url","agentPath":"/workspace/artifacts/alice/task_2026_08_03_x1y2/sqlregistryex-3f9c-aaaa.csv"}')
```

工具返回:

```
CSV 下载链接已生成:
/redirect/download?shortCode=aB3xK9mP2qR5tY8w
请直接点击下载 (链接长期有效).
```

### Step 4 回复用户 (中文)

```
杭州开发二部 2026年7月份版本 Q2-1 数据已查询完成:

- 数据规模: 45 行 × 8 列
- 字段: 项目编号 / 项目名称 / 开发部门 / 版本计划 / 涉及应用 / 产品线 / 统计组 / Q2_1打分状态 / Q2_1是否达标
- 数据日期: 自动取最新 in_date

📥 [点击下载 CSV 完整数据](/redirect/download?shortCode=aB3xK9mP2qR5tY8w)

链接长期有效, 24 小时内任意点击下载.
```

## 验证清单 (开发者用)

执行本 skill 后, 检查以下 5 项全 PASS 才算 CSV 下载能力验证通过:

- [ ] sql_registry_exec 返回的 markdown 表里有 `📦 路径:` 行 + agentPath 完整路径
- [ ] agentPath 复制到 router_tool(generate_csv_download_url) 调用入参后, 工具返回 `CSV 下载链接已生成` + shortCode
- [ ] 浏览器打开 `/redirect/download?shortCode=xxx` 触发文件下载 (不是 404, 不是模拟文本)
- [ ] 下载到本地的 .csv 文件内容 = sql_registry_exec 返回的预览表数据 (行数 / 列名对齐)
- [ ] 文件名含中文时浏览器下载框显示正确 (Content-Disposition filename*=UTF-8 编码生效)

## 失败模式排查

- **`generate_csv_download_url 拒绝: agentPath 必须以 /workspace/artifacts/ 开头`** -- LLM 没从工具结果复制路径, 而是手写。回到 Step 2 重新取数 + 复制完整路径。
- **`router_tool 错误: 未知的 toolId='generate_csv_download_url'`** -- 后端未重启 (CsvDownloadTool 是新加的, 必须 kill + restart mvn spring-boot:run, Spring Boot 不会热加载 .class)。
- **浏览器点链接返回 404** -- shortCode 不存在或已过期; 或 agentPath 对应的 CSV 文件已被 ArtifactSweeper 清理 (任务结束后 6h 自动清)。
- **浏览器点链接返回 400** -- agentPath 含 `..` 或不在 `/workspace/artifacts/` 桶下, 被 RedirectController 二次校验拦截。
- **下载的 CSV 文件名乱码** -- 浏览器不支持 RFC 5987 `filename*=UTF-8''` 编码, 旧版 IE 才有此问题, 现代浏览器 (Chrome/Edge/Firefox) 都支持。

## 注意事项

- **本 skill 仅用于能力验证** -- 真实业务问题 (用户问 "Q2-1 达标率多少") 走 q2_1_by_dept_version_metrics skill 算指标回复, 不要给用户下载链接除非用户明确要"下载"。
- **agentPath 路径必须从工具结果原样复制**, 不要手写, 不要截断, 不要加引号。
- **生成短链前确认 CSV 文件存在** -- 如果 sql_registry_exec 返回 0 行 (total=0), 不会落 CSV, 此时不要调 generate_csv_download_url (会 404)。
- **短链长期有效** -- 当前 UrlShortenerService 不设过期, 链接 24h/7d 后仍能下载, 除非 artifact 文件被 sweeper 清理。
