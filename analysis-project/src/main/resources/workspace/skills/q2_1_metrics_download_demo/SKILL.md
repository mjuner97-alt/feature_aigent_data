---
name: q2_1_metrics_download_demo
description: CSV 下载能力验证 demo - 跑 sql_registry_exec 取 Q2-1 数据后调 generate_csv_download_url 生成短链给用户下载
---

# Q2-1 数据下载 demo (CSV 短链下载能力验证)

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


> ## 示例: 完整 E2E
> 
> 用户问: "杭州开发二部 7月版 Q2-1 数据导出一份给我"
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
> 工具返回 (示例, agentPath 中 user/task 是真实值):
> 
> ```
> [sql_registry_exec] sqlId=q2_1_metrics_by_dept_version params={dept=杭州开发二部, version=2026年7月份版本}
> 
> | 项目编号 | 项目名称 | 开发部门 | 版本计划 | ... |
> |---|---|---|---|---|
> | P001 | xxx系统 | 杭州开发二部 | 2026年7月份版本 | ... |
> | ... |
> 
> [sql_registry_exec] 共 45 行, 耗时 320 ms
> 
> 📦 完整数据已保存为 CSV artifact:
>   路径: /workspace/artifacts/alice/task_2026_08_03_x1y2/sqlregistryex-3f9c-aaaa.csv
>   shape: (45, 8)
> ```
> 
> ### Step 3 调用 (注意 agentPath 必须从上面 "路径:" 行完整复制)
> 
> ```
> router_tool(paramsJson='{"toolId":"generate_csv_download_url","agentPath":"/workspace/artifacts/alice/task_2026_08_03_x1y2/sqlregistryex-3f9c-aaaa.csv"}')
> ```
> 
> 工具返回:
> 
> ```
> CSV 下载链接已生成:
> /redirect/download?shortCode=aB3xK9mP2qR5tY8w
> 请直接点击下载 (链接长期有效).
> ```
> 
> ### Step 4 回复用户 (中文)
> 
> ```
> 杭州开发二部 2026年7月份版本 Q2-1 数据已查询完成:
> 
> - 数据规模: 45 行 × 8 列
> - 字段: 项目编号 / 项目名称 / 开发部门 / 版本计划 / 涉及应用 / 产品线 / 统计组 / Q2_1打分状态 / Q2_1是否达标
> - 数据日期: 自动取最新 in_date
> 
> 📥 [点击下载 CSV 完整数据](/redirect/download?shortCode=aB3xK9mP2qR5tY8w)
> 
> 链接长期有效, 24 小时内任意点击下载.
> ```

## 注意事项

- **本 skill 仅用于能力验证** -- 真实业务问题 (用户问 "Q2-1 达标率多少") 走 q2_1_by_dept_version_metrics skill 算指标回复, 不要给用户下载链接除非用户明确要"下载"。
- **agentPath 路径必须从工具结果原样复制**, 不要手写, 不要截断, 不要加引号。
- **生成短链前确认 CSV 文件存在** -- 如果 sql_registry_exec 返回 0 行 (total=0), 不会落 CSV, 此时不要调 generate_csv_download_url (会 404)。
- **短链长期有效** -- 当前 UrlShortenerService 不设过期, 链接 24h/7d 后仍能下载, 除非 artifact 文件被 sweeper 清理。
